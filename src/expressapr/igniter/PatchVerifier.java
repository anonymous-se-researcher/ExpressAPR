package expressapr.igniter;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class PatchVerifier {
    String workdir;
    String javac_cmdline;
    PreTransformer trans;
    String rel_patched_fn;

    public int compiled_patch_count;
    public int orig_patch_count;

    public PatchVerifier(String patches_json_fn, String workdir, String javac_cmdline) throws IOException {
        this.workdir = workdir;
        this.javac_cmdline = javac_cmdline;
        trans = new PreTransformer(patches_json_fn);
        orig_patch_count = trans.getPatchCount();
        rel_patched_fn = trans.patch_config.getString("filename");

        backupFile(Paths.get(workdir).resolve(rel_patched_fn));
    }

    static private void backupFile(Path fn) throws IOException {
        assert Files.exists(fn) : "file to backup does not exists: "+fn;

        String bkp_fn = fn+".testkit_unpatched";

        if(Files.exists(Paths.get(bkp_fn))) {
            // contain backup: restore this backup
            Files.copy(Paths.get(bkp_fn), fn, StandardCopyOption.REPLACE_EXISTING);
        } else {
            // no backup: make a backup
            Files.copy(fn, Paths.get(bkp_fn));
        }
    }

    private void writePatchedFile(String content) throws IOException {
        Files.write(
            Paths.get(workdir).resolve(rel_patched_fn),
            content.getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private CompileResult compile() throws IOException {
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", javac_cmdline);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        // async thread to read stdout

        List<String> stdout = new ArrayList<>();

        Thread stdout_collector_thread = new Thread(()->{
            BufferedReader stdout_reader = new BufferedReader(new InputStreamReader(p.getInputStream()));

            try {
                String line;
                while ((line=stdout_reader.readLine()) != null)
                    stdout.add(line);
            } catch(IOException ignored) {}
        });
        stdout_collector_thread.start();

        // wait compiler to exits

        try {
            long timeout = Math.min(Args.COMPILE_TIMEOUT_MS_BASE + Args.COMPILE_TIMEOUT_MS_EACH*trans.getPatchCount(), Args.COMPILE_TIMEOUT_MS_MAX);
            boolean completed = p.waitFor(timeout, TimeUnit.MILLISECONDS);
            if(!completed) { // timeout
                System.out.println("compile timeout");
                System.exit(1);
            }
            stdout_collector_thread.join();
        } catch(InterruptedException e) {
            System.out.println("compile thread interrupted");
            e.printStackTrace();
            System.exit(1);
        }

        int errorlevel = p.exitValue();
        if(errorlevel==0) {
            FileWriter f = new FileWriter("compile-succ.txt");
            for(String line: stdout) {
                f.write(line);
                f.write('\n');
            }
            f.close();
            return CompileResult.asSuccess();
        } else {
            FileWriter f = new FileWriter("compile-error.txt");
            for(String line: stdout) {
                f.write(line);
                f.write('\n');
            }
            f.close();

            return CompileResult.parseCompilerFailure(stdout, Paths.get(workdir).toAbsolutePath().toString());
        }
    }

    private Set<Integer> compileAndGetErrorPatchIds(List<Integer> patch_line_table) throws IOException {
        Set<Integer> ret = new HashSet<>();

        System.out.println("compiling");
        CompileResult result = compile();
        if(!result.isSuccess()) {
            List<CompileResult.Failure> error_lines = result.getErrorLines();
            System.out.printf("failed, got %d error lines\n", error_lines.size());

            boolean found_err = false;

            for(CompileResult.Failure failure: error_lines) {
                assert rel_patched_fn.equals(failure.fn) : failure.fn;

                found_err = true;

                int patchid = Collections.binarySearch(patch_line_table, failure.line);
                assert patchid<0; // binarySearch returns negative number if it is not found in list
                patchid = -(patchid+1);

                System.out.printf("- error %s:%d (patch %d): %s\n", failure.fn, failure.line, patchid, failure.errormsg);

                if(!(patchid>=1 && patchid<=trans.getPatchCount())) {
                    // failure not in patched code, probably in ip. let's see if it is a known corner case

                    // missing-return
                    if(failure.errormsg.contains("missing return statement")) {
                        trans.reportInstrumentPointMustReturn();
                    }
                    // var-init
                    else if(failure.errormsg.contains("variable ") && failure.errormsg.contains(" might not have been initialized")) {
                        int idx1 = failure.errormsg.indexOf("variable ") + "variable ".length();
                        int idx2 = failure.errormsg.indexOf(" might not have");
                        String varname = failure.errormsg.substring(idx1, idx2);

                        if(patchid==trans.getPatchCount()+1) {
                            // happens before CODEGEN-IP ends: our stub code refers to an uninitialized var
                            trans.reportUninitializedVar(varname);
                        } else if(patchid==trans.getPatchCount()+2 && trans.container_method_is_constructor) {
                            // happends after CODEGEN-IP ends: trailing code initializes a final field, so ip cannot return
                            trans.reportInstrumentPointCannotReturn();
                        } else {
                            System.out.printf("wtf var not initialized: %s\n", failure.errormsg);
                            System.exit(1);
                        }

                    }
                    // final-var
                    else if(
                        failure.errormsg.contains("cannot assign a value to final variable ")
                        || failure.errormsg.contains(" might already have been assigned")
                    ) {
                        int idx1 = failure.errormsg.indexOf("variable ");
                        assert idx1!=-1;
                        idx1 += "variable ".length();
                        int idx2 = failure.errormsg.indexOf(" ", idx1);
                        if(idx2==-1) // nothing after failed variable
                            idx2 = failure.errormsg.length();
                        String varname = failure.errormsg.substring(idx1, idx2);
                        trans.reportFinalVarAssigned(varname);
                    }
                    // final-param
                    else if(failure.errormsg.contains("final parameter ") && failure.errormsg.contains(" may not be assigned")) {
                        int idx1 = failure.errormsg.indexOf("final parameter ") + "final parameter ".length();
                        int idx2 = failure.errormsg.indexOf(" may not be");
                        String varname = failure.errormsg.substring(idx1, idx2);
                        trans.reportFinalVarAssigned(varname);
                    }
                    // exception-not-thrown
                    else if(failure.errormsg.contains("exception ") && failure.errormsg.contains(" is never thrown in body of corresponding try")) {
                        int idx1 = failure.errormsg.indexOf("exception ") + "exception ".length();
                        int idx2 = failure.errormsg.indexOf(" is never thrown");
                        String expname = failure.errormsg.substring(idx1, idx2);
                        trans.reportCaughtExceptionName(expname);
                    }
                    // control-flow
                    else if(failure.errormsg.contains("break outside switch or loop")) {
                        trans.reportInstrumentPointCannotBreak();
                    }
                    else if(failure.errormsg.contains("continue outside of loop")) {
                        trans.reportInstrumentPointCannotContinue();
                    }
                    // ...
                    else {
                        System.err.println("error not in patch candidates: "+ failure.fn+" at line "+(failure.line)+" : "+(failure.errormsg));
                        System.exit(1);
                    }
                } else {
                    // failure in patch
                    ret.add(patchid);

                    // `() -> {};` in patch will affect parser state and cause nonsense errors after that patch
                    if(failure.errormsg.contains("illegal start of expression"))
                        return ret;
                }

            }

            assert found_err : "compilation failed but no error found";

            return ret;
        } else {
            System.out.println("succ!");
            return null;
        }
    }

    /**
     * @return A list, where insert point of target code will be the patch id (starts from 1)
     */
    private List<Integer> locatePatchesInCode(String code) {
        int patches = trans.getPatchCount();

        List<Integer> ret = new ArrayList<>();

        int lidx = 0;
        for(String line: code.split("\n")) {
            lidx++;
            int mark_idx = line.indexOf(PreTransformer.MARK_CODEGEN_PATCHSTART);
            if(mark_idx!=-1) {
                assert line.substring(mark_idx + PreTransformer.MARK_CODEGEN_PATCHSTART.length())
                    .startsWith(String.valueOf(1+ret.size())) : "patchstart mark not continuous";

                ret.add(lidx);
            }

            int ip_idx = line.indexOf(PreTransformer.MARK_CODEGEN_IP_STUB);
            if(ip_idx!=-1) {
                assert patches+1 == ret.size() : "ip mark not after patches";

                ret.add(lidx);
            }
        }

        assert patches+2 == ret.size() : "patches number mismatch";

        return ret;
    }

    /**
     * Verify each patch, keeping only compileable patches.
     * Will initialize `compiled_patch_count` and `tree_handleable`.
     */
    public void verifyAllAndWriteFile() {
        compiled_patch_count = -2;

        trans.preparePatches();
        trans.precheckPatches();

        try {
            assert compile().isSuccess() : "unpatched code does not compile";

            while(true) {
                String code = trans.generatePatches();
                writePatchedFile(code);
                List<Integer> patch_line_table = locatePatchesInCode(code);

                Set<Integer> failed_ids = compileAndGetErrorPatchIds(patch_line_table);
                if(failed_ids==null)
                    break;

                /*
                if(trans.getPatchCount()==0) {
                    // failed after all patches are removed
                    // give another chance, in case some flag is just updated in this run
                    String c = trans.generatePatches();
                    writePatchedFile(c);
                    CompileResult cr = compile();

                    if(!cr.isSuccess()) {
                        System.out.println("code compile error");
                        for(CompileResult.Failure f: cr.getErrorLines())
                            System.out.println(f);
                        System.exit(1);
                    }
                }
                */

                trans.removePatches(failed_ids);
                System.out.printf("removed %d patches, %d left\n", failed_ids.size(), trans.getPatchCount());

                if(trans.getPatchCount()==0)
                    break;
            }
        } catch(IOException e) {
            e.printStackTrace();
            compiled_patch_count = -1;
            return;
        }

        compiled_patch_count = trans.getPatchCount();
    }

    public String getStatusLine() {
        char[] status = new char[orig_patch_count];
        assert compiled_patch_count<=orig_patch_count;

        for(int i=1; i<=orig_patch_count; i++)
            status[i-1] = 'C';

        for(int pid: trans.orig_patchids)
            status[pid-1] = '?';

        return new String(status);
    }
}
