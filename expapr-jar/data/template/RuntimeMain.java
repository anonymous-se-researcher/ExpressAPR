package expressapr.testkit;

import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import junit.framework.TestCase;
import org.junit.runner.notification.Failure;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;

import java.edu.columbia.cs.psl.vmvm.runtime.Reinitializer;

public class Main {
    JUnitCore core = new JUnitCore();
    TestKitOrchestrator orchestrator = TestKitOrchestrator.v();

    final Class<?>[] TEST_CLASSES = {
        [[[TEST_CLASSES]]]
    };
    final Test[] TRIGGERING_TESTS = {
        [[[TRIGGERING_TESTS]]]
    };
    HashSet<Test> BUILTIN_TESTS_SET;
    int PATCHES = [[[PATCH_COUNT]]];
    TestResult[] test_results;
    boolean[] tree_handleable = {
        false, // skip [0]
    [[[TREE_HANDLEABLE]]]
    };

    final long SINGLE_TEST_TIMEOUT_SEC = 180;
    boolean all_patches_killed = false;

    int TELEMETRY_actual_count = 0;
    int TELEMETRY_tree_save_count = 0;
    int TELEMETRY_sel_save_count = 0;

    long TELEMETRY_total_schemata_time_nano = 0;
    long TELEMETRY_total_user_time_nano = 0;
    long TELEMETRY_total_test_time_nano = 0;

    public void the_main(String[] args) {
        long start_time = System.currentTimeMillis();

        BUILTIN_TESTS_SET = new HashSet<Test>();
        for(Test t: TRIGGERING_TESTS)
            BUILTIN_TESTS_SET.add(t);

        test_results = new TestResult[PATCHES+1];
        for(int i=1; i<=PATCHES; i++)
            test_results[i] = TestResult.Expanding;

        long test_start_time = System.currentTimeMillis();

        [[[--]]] int tidx = 1;

        for(Test test: TRIGGERING_TESTS) {
            [[[--]]] System.out.printf("** [%d] TRIGGERING TEST %s :: %s\n", tidx, test.get_clazz().getCanonicalName(), test.get_method());
            run_test(test);
            [[[--]]] tidx++;

            if(all_patches_killed)
                break;
        }

        if(!all_patches_killed) {
            boolean some_patch_not_killed = false;
            for(int pid=1; pid<=PATCHES; pid++)
                if(test_results[pid]==TestResult.Expanding) {
                    some_patch_not_killed = true;
                    break;
                }

            if(some_patch_not_killed) {
                outer:
                for(Class<?> cls: TEST_CLASSES) {
                    for(Test test: get_all_tests(cls)) {
                        [[[--]]] System.out.printf("** [%d] TEST %s :: %s\n", tidx, test.get_clazz().getCanonicalName(), test.get_method());
                        run_test(test);
                        [[[--]]] tidx++;

                        if(all_patches_killed)
                            break outer;
                    }
                }
            }
        }

        long stop_time = System.currentTimeMillis();

        System.out.println("RUNTEST DONE! ==");

        for(int i=1; i<=PATCHES; i++) {
            if(test_results[i]==TestResult.Expanding) // not killed by any test
                test_results[i] = TestResult.Passed;
        }
        print_test_result();

        System.out.print("Time: ");
        System.out.println(stop_time-start_time);
        System.out.printf(
            "Telemetry: %d %d %d %d %d %d %d %d\n",
            TELEMETRY_actual_count, TELEMETRY_tree_save_count, TELEMETRY_sel_save_count,
            TELEMETRY_total_schemata_time_nano, TELEMETRY_total_user_time_nano,
            stop_time-test_start_time, test_start_time-start_time,
            TELEMETRY_total_test_time_nano
        );
        System.exit(0);
    }

    void print_test_result() {
        System.out.print("patch status: ");
        for(int i=1; i<=PATCHES; i++) {
            System.out.print(test_results[i]==TestResult.Passed ? "s" : "F");
        }
        System.out.print("\n");
    }

    boolean is_test_buggy(String cls, String method) {
        return (
            (
                (cls.equals("org.apache.commons.lang3.ClassUtilsTest") || cls.equals("org.apache.commons.lang.ClassUtilsTest"))
                && method.equals("testShowJavaBug")
            ) // shows a jvm bug that no longer exists

            || (
            method.toLowerCase().contains("concurren") || cls.toLowerCase().contains("concurren")
            ) // we cannot handle unstable tests yet

            || (
                cls.equals("org.apache.commons.lang.EntitiesPerformanceTest")
            ) // lots of flaky tests because object under test is not initialized across methods

            || (
                cls.equals("org.apache.commons.lang.enums.EnumUtilsTest")
            ) // lots of flaky tests that depends on the fact that ColorEnum is `clinit`ed somewhere else

            || (
                cls.equals("org.apache.commons.lang.time.DateFormatUtilsTest") && method.equals("testLang312")
            ) // test result depends on the system timezone
        );
    }

    ArrayList<Test> get_all_tests(Class<?> cls) {
        ArrayList<Test> TESTS = new ArrayList<Test>();

        // https://stackoverflow.com/questions/2635839/junit-confusion-use-extends-testcase-or-test
        boolean is_junit3_testclass = TestCase.class.isAssignableFrom(cls);
        for(Method method: cls.getMethods()) {
            if(
                is_junit3_testclass ?
                    (method.getName().startsWith("test") && method.getParameterTypes().length==0) :
                    method.isAnnotationPresent(org.junit.Test.class)
            ) {
                if(!is_test_buggy(cls.getName(), method.getName())) {
                    Test test = new Test(cls, method.getName());
                    if(!BUILTIN_TESTS_SET.contains(test))
                        TESTS.add(test);
                }
            }
        }

        return TESTS;
    }

    void run_test(Test test) {
        assert test.tree.get_subtree_expanding_count()==1; // tree should have single root node in expanding status

        // gen tree patches
        boolean some_patch_not_killed = false;
        for(int pid=1; pid<=PATCHES; pid++) {
            if(test_results[pid]==TestResult.Expanding) {
                some_patch_not_killed = true;
                if(tree_handleable[pid])
                    test.tree.add_into_patches(pid);
            }
        }
        if(!some_patch_not_killed) {
            all_patches_killed = true;
            return;
        }

        boolean fallback_to_seq = false;
        [[[--]]] boolean contains_failure = false;

        if(test.tree.get_patches().size()>0) {
            // run tree patches
            while(test.tree.get_subtree_expanding_count()>0) {
                int expanding_count = test.tree.get_subtree_expanding_count();
                [[[--]]] System.out.printf("invoking tree run (expanding count: %d)\n", expanding_count);
                [[[--]]] TELEMETRY_actual_count++;

                Reinitializer.markAllClassesForReinit();
                orchestrator = TestKitOrchestrator.v();

                orchestrator.mark_tree_run(test.tree);
                orchestrator.begin_tree_run();

                TestResult tr = run_real_test[[[<!--]]]_timed[[[-->]]](test, SINGLE_TEST_TIMEOUT_SEC*expanding_count);
                [[[--]]] if(tr==TestResult.Failed) contains_failure = true;

                orchestrator.end_tree_run(tr);

                [[[--]]] TELEMETRY_total_schemata_time_nano += orchestrator.TELEMETRY_total_schemata_time_nano;
                [[[--]]] TELEMETRY_total_user_time_nano += orchestrator.TELEMETRY_total_user_time_nano;

                if(orchestrator.get_fatal()!=null) {
                    [[[--]]] System.out.printf("FATAL: %s\n", orchestrator.get_fatal());
                    fallback_to_seq = true;
                    break;
                }
            }

            if(!fallback_to_seq) {
                [[[--]]] if(contains_failure) print_verbose_tree(test.tree, 0, "init");

                [[[--]]] int old_cnt = TELEMETRY_tree_save_count;

                // collect tree results
                collect_tree_result_recursive(test.tree);

                [[[<!--]]]
                if(test.tree.get_result()!=TestResult.InvokeExpanded) { // revert count, because tree ends at root node
                    int diff = TELEMETRY_tree_save_count - old_cnt;
                    TELEMETRY_tree_save_count = old_cnt;
                    TELEMETRY_sel_save_count += diff;
                }
                [[[-->]]]
            }
        }

        TestResult root_result = fallback_to_seq ? TestResult.Expanding : test.tree.get_result();

        // process patches not handled by tree
        for(int pid=1; pid<=PATCHES; pid++) {
            if(tree_handleable[pid] && !fallback_to_seq) // covered by tree
                continue;
            if(test_results[pid]==TestResult.Failed) // killed by prev test
                continue;
    
            // root_result will be Expanding if all patches have been failed on previous tests
            if([[[TEST_SEL_WHEN_SINGLE_RUN]]] && !(root_result==TestResult.InvokeExpanded || root_result==TestResult.Expanding)) {
                [[[--]]] System.out.printf("skipped single run because root state is %s\n", root_result);
                [[[--]]] TELEMETRY_sel_save_count++;

                if(root_result==TestResult.Failed)
                    test_results[pid] = root_result;

                continue;
            }

            [[[--]]] System.out.printf("invoking single run for patch #%d\n", pid);
            [[[--]]] TELEMETRY_actual_count++;

            Reinitializer.markAllClassesForReinit();
            orchestrator = TestKitOrchestrator.v();

            orchestrator.mark_single_run(pid);
            orchestrator.begin_single_run();

            TestResult tr = run_real_test[[[<!--]]]_timed[[[-->]]](test, SINGLE_TEST_TIMEOUT_SEC);

            [[[--]]] TELEMETRY_total_schemata_time_nano += orchestrator.TELEMETRY_total_schemata_time_nano;
            [[[--]]] TELEMETRY_total_user_time_nano += orchestrator.TELEMETRY_total_user_time_nano;

            if(tr==TestResult.Failed)
                test_results[pid] = tr;

            if(!orchestrator.single_run_touched)
                root_result = tr;
        }
    }

    void collect_tree_result_recursive(DecisionTree node) {
        TestResult tr = node.get_result();
        if(tr==TestResult.Failed) {
            for(int pid: node.get_patches()) {
                assert test_results[pid]==TestResult.Expanding;
                [[[--]]] System.out.printf("marking patch #%d as failed\n", pid);

                test_results[pid] = TestResult.Failed;
            }
            assert node.get_patches().size()>0 : "wtf node has empty patch set";
            [[[--]]] TELEMETRY_tree_save_count += node.get_patches().size()-1;
        } else if(tr==TestResult.InvokeExpanded) {
            for(Map.Entry<InvokeDetails, DecisionTree> edge: node.get_childs())
                collect_tree_result_recursive(edge.getValue());
        } else {
            assert tr!=TestResult.Expanding; // expecting no expanding node as the tree is fully expanded

            // a passed node
            assert node.get_patches().size()>0 : "wtf node has empty patch set";
            [[[--]]] TELEMETRY_tree_save_count += node.get_patches().size()-1;
        }
    }

    static boolean is_junit_result_skipped(Result res) {
        if(res.getFailureCount()==1) {
            Failure f = res.getFailures().get(0);
            String msg = f.getMessage();
            if(msg!=null && msg.contains("No tests found matching Method "))
                return true;
        }
        return false;
    }

    TestResult run_real_test(final Test test, long timeout_sec) {
        class TestTask implements Callable<Result> {
            @Override
            public Result call() {
                return core.run(Request.method(test.get_clazz(), test.get_method()));
            }
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Result> future = executor.submit(new TestTask());

        try {
            Result result = future.get(timeout_sec, TimeUnit.SECONDS);

            TestResult ret;

            if(is_junit_result_skipped(result)) {
                [[[--]]] System.out.print("> skipped");

                ret = TestResult.Passed;
            } else if(!result.wasSuccessful()) {
                [[[<!--]]] System.out.print("> failed ");
                for(Failure f: result.getFailures()) {
                    System.out.print("{");
                    System.out.print(f);
                    System.out.print("} ");
                }
                [[[-->]]]

                ret = TestResult.Failed;
            } else {
                [[[--]]] System.out.print("> passed ");

                ret = TestResult.Passed;
            }
            [[[--]]] System.out.println("");

            return ret;
        } catch (ExecutionException e) {
            e.printStackTrace();
            return TestResult.Failed;
        } catch (InterruptedException e) {
            System.out.println("! test interrupted");
            future.cancel(true);
            return TestResult.Failed;
        } catch (TimeoutException e) {
            System.out.println("! test timeout");
            future.cancel(true);
            return TestResult.Failed;
        }
    }

    TestResult run_real_test_timed(final Test test, long timeout_sec) {
        long begin_ts = java.lang.System.nanoTime();
        TestResult r = run_real_test(test, timeout_sec);
        long end_ts = java.lang.System.nanoTime();
        TELEMETRY_total_test_time_nano += end_ts-begin_ts;
        return r;
    }

    void print_verbose_tree(DecisionTree node, int indent, String edge_in_desc) {
        System.out.print('|');
        for(int i=0; i<indent; i++)
            System.out.print("  ");
        if(indent>20) {
            System.out.printf("...  (patch#: %s)\n", node.get_patches().toString());
            return;
        }
        System.out.print("= ");
        switch(node.get_result()) {
            case Expanding:
                System.out.print("EXPD ");
                break;
            case InvokeExpanded:
                System.out.print("CALL ");
                break;
            case Passed:
                System.out.print("SUCC ");
                break;
            case Failed:
                System.out.print("FAIL ");
                break;
        }
        System.out.printf("(%s)\n", edge_in_desc);

        for(Map.Entry<InvokeDetails, DecisionTree> edge: node.get_childs()) {
            StringBuilder desc = new StringBuilder();
            desc.append(edge.getKey().get_res().toString());
            desc.append(" ");
            desc.append(edge.getKey().get_fields_changed().toString());
            print_verbose_tree(edge.getValue(), indent+1, desc.toString());
        }

        if(node.get_childs().isEmpty()) {
            System.out.print('|');
            for(int i=0; i<indent; i++)
                System.out.print("  ");
            System.out.printf("  (patch#: %s)\n", node.get_patches().toString());
        }
    }

    // be careful that all static fields are fucked by vmvm
    public static void main(String[] args) {
        Main m = new Main();
        m.the_main(args);
    }
}