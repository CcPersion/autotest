import com.autotest.runner.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;

var mapper = new ObjectMapper();
var dir = Path.of("/tmp/f2-01-http-real");
var compiler = new JmeterPlanCompiler();
var target = "http://f2-http-target:8080";

var form = mapper.readTree("[{\"name\":\"username\",\"value\":\"alice\",\"enabled\":true}]");
compiler.compile(new JmeterPlan("real-form", "step-form", target, "POST", "/form",
        List.of(), List.of(), new JmeterBody("URLENCODED", form), Map.of(),
        List.of(new JmeterAssertion("STATUS", "EQUALS", null, mapper.readTree("200")))),
        dir.resolve("form.jmx"));

var multipart = mapper.readTree("{\"fields\":[{\"name\":\"title\",\"value\":\"report\",\"enabled\":true}],\"files\":[{\"name\":\"attachment\",\"path\":\"/work/report.txt\",\"mimeType\":\"text/plain\"}]}");
compiler.compile(new JmeterPlan("real-multipart", "step-multipart", target, "POST", "/multipart",
        List.of(), List.of(), new JmeterBody("MULTIPART", multipart), Map.of(),
        List.of(new JmeterAssertion("STATUS", "EQUALS", null, mapper.readTree("200")))),
        dir.resolve("multipart.jmx"));

compiler.compile(new JmeterPlan("real-cookie", "step-cookie", target, "POST", "/cookie",
        List.of(), List.of(), JmeterBody.none(), Map.of(),
        List.of(new JmeterAssertion("STATUS", "EQUALS", null, mapper.readTree("200"))),
        List.of(new JmeterCookie("session", "abc123")), true, 0, 0, null),
        dir.resolve("cookie.jmx"));

compiler.compile(new JmeterPlan("real-redirect", "step-redirect", target, "GET", "/redirect",
        List.of(), List.of(), JmeterBody.none(), Map.of(),
        List.of(new JmeterAssertion("STATUS", "EQUALS", null, mapper.readTree("200"))),
        List.of(), true, 0, 0, null), dir.resolve("redirect.jmx"));

compiler.compile(new JmeterPlan("real-timeout", "step-timeout", target, "GET", "/delay",
        List.of(), List.of(), JmeterBody.none(), Map.of(),
        List.of(new JmeterAssertion("STATUS", "EQUALS", null, mapper.readTree("200"))),
        List.of(), true, 0, 500, null), dir.resolve("timeout.jmx"));

compiler.compile(new JmeterPlan("compile-proxy", "step-proxy", target, "GET", "/ok",
        List.of(), List.of(), JmeterBody.none(), Map.of(), List.of(), List.of(), true, 0, 0,
        new JmeterProxy("http", "f2-http-proxy", 8081, "", "")),
        dir.resolve("proxy.jmx"));

System.out.println("generated=" + Files.list(dir).filter(path -> path.toString().endsWith(".jmx")).count());
