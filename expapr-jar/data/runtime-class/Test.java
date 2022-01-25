package expressapr.testkit;

public class Test {
    private Class<?> clazz;
    private String method;
    DecisionTree tree;

    Test(Class<?> clazz, String method) {
        this.clazz = clazz;
        this.method = method;
        this.tree = new DecisionTree(TestResult.Expanding, null);
    }

    public Class<?> get_clazz() {
        return clazz;
    }
    public String get_method() {
        return method;
    }
    public DecisionTree get_tree() {
        return tree;
    }

    public boolean equals(Object obj) {
        if (obj instanceof Test) {
            Test other = (Test) obj;
            return clazz.equals(other.clazz) && method.equals(other.method);
        }
        return false;
    }
    public int hashCode() {
        // not `Objects.hash` here because it is JDK 1.7+
        return clazz.hashCode() + method.hashCode();
    }
}
