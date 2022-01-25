package expressapr.igniter;

public class Args {
    // PatchVerifier
    final static long COMPILE_TIMEOUT_MS_EACH = 5000;
    final static long COMPILE_TIMEOUT_MS_MAX = 180000;
    final static long COMPILE_TIMEOUT_MS_BASE = 30000;

    // StringTemplate
    public static boolean RUNTIME_DEBUG = false;

    // TreeStringify
    final static boolean USE_LEXICAL_PRINTING = false;

    // SideEffectAnalyzer
    final static boolean ONLY_SAFE_TYPES_IN_TREE = true;
    final static boolean TREAT_ALL_METHODS_AS_DANGEROUS = false;
}
