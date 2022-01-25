package expressapr.igniter;

import java.util.Objects;

public class Variable {
    public String type;
    public String name;
    public boolean is_final;

    static public final String NAME_PREFIX = "_testkitlocal_";

    @Override
    public String toString() {
        return "Variable{" +
            "type='" + type + '\'' +
            ", name='" + name + '\'' +
            ", is_final=" + is_final +
            '}';
    }

    public Variable(String type, String name, boolean is_final) {
        this.type = type;
        assert name!=null;
        this.name = name;
        this.is_final = is_final;
    }

    public static Variable pseudoVarForSearch(String name) {
        return new Variable(null, name, false);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Variable variable = (Variable) o;
        return name.equals(variable.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    public String get_declare_statement(boolean is_static) {
        assert type!=null : "pseudo var for search cannot be declared";
        return "private "+(is_static ? "static " : "")+type+" "+NAME_PREFIX+name+";";
    }
}
