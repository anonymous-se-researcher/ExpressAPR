package expressapr.igniter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class StringTemplate {
    private String name;
    private String content;
    private StringTemplate(String content, String name) {
        this.content = content;
        this.name = name;
    }

    public static StringTemplate fromTemplateName(String name) {
        String str = "";
        try {
            str = new String(Files.readAllBytes(Paths.get("data/template/"+name+".java")));
        } catch(IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
        return (
            new StringTemplate(str.replace("\r\n", "\n"), name)
                .maybeSet("<!--", Args.RUNTIME_DEBUG ? "" : "/*")
                .maybeSet("-->", Args.RUNTIME_DEBUG ? "" : "*/")
                .maybeSet("--", Args.RUNTIME_DEBUG ? "" : "//")
        );
    }

    private StringTemplate maybeSet(String k, String v) {
        return new StringTemplate(
            content.replace("[[["+k+"]]]", v),
            name
        );
    }

    public StringTemplate set(String k, String v) {
        assert content.contains("[[["+k+"]]]") : k;
        return maybeSet(k, v);
    }

    public String done() {
        int idx = content.indexOf("[[[");
        if(idx!=-1) {
            int idx2 = content.indexOf("]]]");
            System.out.printf("unfilled template param: %s\n", content.substring(idx+3, idx2));
            System.exit(1);
        }
        return content;
    }

    public String doneWithNewline() {
        String str = done();
        if(!str.endsWith("\n"))
            str = str+"\n";
        return str;
    }
}
