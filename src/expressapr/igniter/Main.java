package expressapr.igniter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class Main {
    static String patches_json_fn = "";
    static String project_root_path = "";
    static String project_src_path = "";
    static String project_test_path = "";
    static String project_vendor_path = "testkit_lib";
    static String javac_cmdline = "";
    static List<String> related_test_classes;
    static List<String> triggering_tests;

    public static long total_offline_time_ns = 0;

    static final String[] COPY_RUNTIME_CLASSNAMES = {
        "DecisionTree",
        "InvokeDetails",
        "Test",
        "TestKitExecResult",
        "TestKitOrchestrator",
        "TestResult",
        "internal_exceptions/PatchBreak",
        "internal_exceptions/PatchContinue",
        "internal_exceptions/PatchFinish",
    };

    public static void main(String[] args) throws IOException {
        patches_json_fn = args[0]; //"test-domath/patches.json";
        project_root_path = args[1]; //"test-domath";
        related_test_classes = Arrays.asList(args[2].split("\\|")); //new ArrayList<String>(Arrays.asList("jvav.ICanDoMathTest"));
        project_src_path = args[3]; //"src";
        project_test_path = args[4]; //"test";
        javac_cmdline = args[5]; //"javac";
        String dedup_flag = args[6]; //"dedup-on"/"dedup-off";
        SideEffectAnalyzer.sidefx_db_path = args[7]; //"xxx.csv";
        triggering_tests = Arrays.asList(args[8].split("\\|"));

        assert project_src_path.charAt(0)!='/' : "src path should be relative to project root path";
        assert project_test_path.charAt(0)!='/' : "test path should be relative to project root path";
        assert dedup_flag.equals("dedup-on") || dedup_flag.equals("dedup-off");

        SideEffectAnalyzer.src_absolute_path = project_root_path+"/"+project_src_path;
        SideEffectAnalyzer.nodedup_mode = dedup_flag.equals("dedup-off");

        if(Args.RUNTIME_DEBUG)
            System.out.println("!!! RUNTIME_DEBUG is on, performance is not guaranteed");

        long start_time_ = System.currentTimeMillis();

        copyRuntimeFiles();
        copyVendorFiles();
        PatchVerifier v = generatePatchedClass(patches_json_fn, project_root_path, javac_cmdline);
        genRuntimeMain(v);

        long stop_time_ = System.currentTimeMillis();

        // print stats

        System.out.println("PREPROCESS DONE! ==");

        System.out.print("preprocess time (ms): ");
        System.out.println(stop_time_-start_time_);

        System.out.print("patch count left: ");
        System.out.println(v.compiled_patch_count);

        System.out.print("patch status: ");
        System.out.println(v.getStatusLine());

        int tree_handleable_cnt = 0;
        for(boolean h: v.trans.tree_handleable)
            if(h)
                tree_handleable_cnt++;

        System.out.printf("Telemetry: %d %d\n", tree_handleable_cnt, total_offline_time_ns);
    }

    static void copyRuntimeFiles() throws IOException {
        // copy runtime files to both src dir and test dir,
        // otherwise some runtime `.class`es not used in src may not be generated in `compile` phase,
        // causing an exception in `compile-tests` phase

        Files.createDirectories(Paths.get(project_root_path+"/"+project_src_path+"/expressapr/testkit"));
        Files.createDirectories(Paths.get(project_root_path+"/"+project_src_path+"/expressapr/testkit/internal_exceptions"));
        Files.createDirectories(Paths.get(project_root_path+"/"+project_test_path+"/expressapr/testkit"));
        Files.createDirectories(Paths.get(project_root_path+"/"+project_test_path+"/expressapr/testkit/internal_exceptions"));

        for(String fn: COPY_RUNTIME_CLASSNAMES) {
            Files.copy(
                Paths.get("data/runtime-class/"+fn+".java"),
                Paths.get(project_root_path+"/"+project_src_path+"/expressapr/testkit/"+fn+".java"),
                StandardCopyOption.REPLACE_EXISTING
            );

            Files.copy(
                Paths.get("data/runtime-class/"+fn+".java"),
                Paths.get(project_root_path+"/"+project_test_path+"/expressapr/testkit/"+fn+".java"),
                StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    static void copyVendorFiles() throws IOException {
        Files.createDirectories(Paths.get(project_root_path+"/"+project_vendor_path));

        File jars = new File("data/runtime-vendor");
        for(File file: Objects.requireNonNull(jars.listFiles())) {
            Files.copy(
                file.toPath(),
                Paths.get(project_root_path+"/"+project_vendor_path+"/"+file.getName()),
                StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    static PatchVerifier generatePatchedClass(String patches_json_fn, String workdir, String javac_cmdline) throws IOException {
        PatchVerifier v = new PatchVerifier(patches_json_fn, workdir, javac_cmdline);
        v.verifyAllAndWriteFile();
        System.out.printf("== got %d compiled patch\n", v.compiled_patch_count);
        //assert v.compiled_patch_count>=0 : "verifier reports error"; // used for debugging, remove that in production
        return v;
    }

    static void genRuntimeMain(PatchVerifier v) throws IOException {
        StringBuilder test_classes_sb = new StringBuilder();
        for(String cl: related_test_classes)
            test_classes_sb.append(cl).append(".class,");

        StringBuilder triggering_tests_sb = new StringBuilder();
        for(String t: triggering_tests) { // like `com.company.TestClass::testMethod1`
            String[] decl = t.split("::");
            assert decl.length==2;
            triggering_tests_sb.append(String.format("new Test(%s.class,\"%s\"),", decl[0], decl[1]));
        }

        StringBuilder tree_handleable_sb = new StringBuilder();
        for(boolean hd: v.trans.tree_handleable)
            tree_handleable_sb.append(hd ? "true" : "false").append(", ");

        Files.createDirectories(Paths.get(project_root_path+"/"+project_test_path+"/expressapr/testkit"));
        FileWriter writer = new FileWriter(project_root_path+"/"+project_test_path+"/expressapr/testkit/Main.java");

        writer.write(
            StringTemplate.fromTemplateName("RuntimeMain")
                .set("TEST_CLASSES", test_classes_sb.toString())
                .set("TRIGGERING_TESTS", triggering_tests_sb.toString())
                .set("TREE_HANDLEABLE", tree_handleable_sb.toString())
                .set("PATCH_COUNT", String.valueOf(v.compiled_patch_count))
                .set("TEST_SEL_WHEN_SINGLE_RUN", SideEffectAnalyzer.nodedup_mode ? "false" : "true")
                .doneWithNewline()
        );
        writer.close();
    }
}
