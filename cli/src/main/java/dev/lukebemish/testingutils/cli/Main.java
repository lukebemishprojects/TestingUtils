package dev.lukebemish.testingutils.cli;

import org.opentest4j.reporting.schema.Namespace;
import org.opentest4j.reporting.tooling.core.htmlreport.DefaultHtmlReportWriter;
import org.w3c.dom.CharacterData;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import picocli.CommandLine;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

@CommandLine.Command(name = "testingutils", mixinStandardHelpOptions = true)
public class Main implements Callable<Integer> {
    @CommandLine.Command(name = "html", description = "Process files into HTML report")
    public void status(
        @CommandLine.Parameters(index = "0", arity = "1", description = "Output file")
        Path output,
        @CommandLine.Parameters(index = "1..*", arity = "1..", description = "List of report files to process")
        List<Path> reports
    ) throws Exception {
        var writer = new DefaultHtmlReportWriter();
        writer.writeHtmlReport(reports, output);
    }

    private static final Namespace TESTINGUTILS = Namespace.of("https://schemas.lukebemish.dev/testingutils/0.1.0");

    private static final Pattern STACKTRACE_AT_LINE = Pattern.compile(
        "^\\s*at\\s+(([a-zA-Z0-9$_.]+)/)?([a-zA-Z0-9$_.]+)\\.([a-zA-Z0-9$_]+)\\(([a-zA-Z0-9$_]+)\\.([a-zA-Z0-9$_]+):([0-9]+)\\)$"
    );

    @CommandLine.Command(name = "annotate", description = "Process results into GitHub action annotations")
    public void annotate(
        @CommandLine.Parameters(index = "0", arity = "1", description = "Code location")
        Path codeLocation,
        @CommandLine.Parameters(index = "1", arity = "1", description = "Source roots, as path")
        String sourceRoots,
        @CommandLine.Parameters(index = "2..*", arity = "1..", description = "List of report files to process")
        List<Path> reports
    ) throws Exception {
        var potentialSourceRoots = sourceRoots.split(":");
        Map<String, Set<String>> annotated = new HashMap<>();
        for (var path : reports) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();

            Document sourceDocument = builder.parse(path.toFile());
            Element element = sourceDocument.getDocumentElement();

            if (!"events".equals(element.getLocalName()) || !Namespace.REPORTING_EVENTS.getUri().equals(element.getNamespaceURI())) {
                throw new IllegalArgumentException("Invalid report file: " + path);
            }

            String testingUtilsId = null;

            var infrastructureNodes = getChildrenByNameNS(element, "infrastructure", Namespace.REPORTING_CORE.getUri());
            if (!infrastructureNodes.isEmpty()) {
                var node = infrastructureNodes.getFirst();
                var testingUtilsNodes = getChildrenByNameNS(node, "id", TESTINGUTILS.getUri());
                if (!testingUtilsNodes.isEmpty()) {
                    testingUtilsId = testingUtilsNodes.getFirst().getTextContent();
                }
            }

            Map<Integer, String> eventNames = new HashMap<>();
            Map<Integer, Integer> eventParents = new HashMap<>();
            var started = getChildrenByNameNS(element, "started", Namespace.REPORTING_EVENTS.getUri());
            for (var event : started) {
                var id = Integer.parseInt(event.getAttribute("id"));
                var parentId = event.getAttribute("parentId");
                if (!parentId.isEmpty()) {
                    eventParents.put(id, Integer.parseInt(parentId));
                }
                var name = event.getAttribute("name");
                eventNames.put(id, name);
            }
            var finished = getChildrenByNameNS(element, "finished", Namespace.REPORTING_EVENTS.getUri());
            for (var event : finished) {
                var id = Integer.parseInt(event.getAttribute("id"));
                var results = getChildrenByNameNS(event, "result", Namespace.REPORTING_CORE.getUri());
                if (!results.isEmpty()) {
                    var result = results.getFirst();
                    var status = result.getAttribute("status");
                    if (status.equals("FAILED") || status.equals("ERRORED") || status.equals("ABORTED")) {
                        var throwables = getChildrenByNameNS(result, "throwable", Namespace.REPORTING_JAVA.getUri());
                        if (!throwables.isEmpty()) {
                            var throwable = throwables.getFirst();
                            var stackTrace = getCharacterDataFromElement(throwable);
                            for (var line : (Iterable<String>) stackTrace.lines()::iterator) {
                                // We annotate every location involved in the stack trace

                                var matcher = STACKTRACE_AT_LINE.matcher(line);
                                if (matcher.matches()) {
                                    var className = matcher.group(3);
                                    var fileName = matcher.group(5);
                                    var extension = matcher.group(6);
                                    var lineNumber = matcher.group(7);

                                    var classBinaryName = className.replace('.', '/');
                                    var rest = classBinaryName.substring(0, classBinaryName.lastIndexOf('/'));
                                    var relative = rest + "/" + fileName + "." + extension;
                                    for (var root : potentialSourceRoots) {
                                        // We place an annotation at every matching path, as we don't know which one it is...

                                        var relativePath = codeLocation.resolve(root).resolve(relative);
                                        if (Files.exists(relativePath)) {
                                            var parts = new ArrayList<String>();
                                            Integer currentId = id;
                                            while (currentId != null) {
                                                parts.add(eventNames.getOrDefault(currentId, "<unknown>"));
                                                currentId = eventParents.get(currentId);
                                            }
                                            if (testingUtilsId != null) {
                                                parts.add(testingUtilsId);
                                            }
                                            var message = status+": "+String.join(" > ", parts.reversed());

                                            var relativePathString = codeLocation.relativize(relativePath).toString();
                                            var list = annotated.computeIfAbsent(relativePathString, k -> new HashSet<>());
                                            if (list.add(message)) {
                                                System.out.println(
                                                    "::error file=" + relativePathString + ",line=" + lineNumber + "::" + message
                                                );
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static String getCharacterDataFromElement(Element e) {
        Node child = e.getFirstChild();
        if (child instanceof CharacterData characterData) {
            return characterData.getData();
        }
        return "";
    }

    private static List<Element> getChildrenByNameNS(Element element, String name, String namespaceUri) {
        var child = element.getFirstChild();
        var nodes = new ArrayList<Element>();
        do {
            if (child instanceof Element childElement && name.equals(childElement.getLocalName()) && namespaceUri.equals(childElement.getNamespaceURI())) {
                nodes.add(childElement);
            }
            child = child.getNextSibling();
        } while (child != null);
        return nodes;
    }

    private static String escapeData(String s) {
        return s.replace("%", "%25")
            .replace("\r", "%0D")
            .replace("\n", "%0A");
    }

    @Override
    public Integer call() {
        System.out.println("Subcommand needed; use --help for more information.");
        return -1;
    }

    public static void main(String[] args) {
        int rc = new CommandLine(new Main()).execute(args);
        System.exit(rc);
    }
}
