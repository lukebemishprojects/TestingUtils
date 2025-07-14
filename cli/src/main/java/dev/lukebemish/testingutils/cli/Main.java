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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@CommandLine.Command(name = "testingutils", mixinStandardHelpOptions = true)
public class Main implements Callable<Integer> {
    @CommandLine.Command(name = "html", description = "Process files into HTML report")
    public void html(
        @CommandLine.Parameters(index = "0", arity = "1", description = "Output file")
        Path output,
        @CommandLine.Parameters(index = "1..*", arity = "1..", description = "List of report files to process")
        List<Path> reports
    ) throws Exception {
        var writer = new DefaultHtmlReportWriter();
        writer.writeHtmlReport(reports, output);
    }

    private static final Namespace TESTINGUTILS = Namespace.of("https://schemas.lukebemish.dev/testingutils/0.1.0");
    private static final Namespace JUNIT = Namespace.of("https://schemas.junit.org/open-test-reporting");

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
                            int lastLineOffset = 0;
                            boolean lastWasLayerBuilderExecute = false;
                            for (var line : (Iterable<String>) stackTrace.lines()::iterator) {
                                // We annotate every location involved in the stack trace

                                var matcher = STACKTRACE_AT_LINE.matcher(line);
                                if (matcher.matches()) {
                                    var className = matcher.group(3);
                                    var fileName = matcher.group(5);
                                    var extension = matcher.group(6);
                                    var lineNumber = matcher.group(7);

                                    if ("dev.lukebemish.testingutils.framework.modulelayer.LayerBuilder".equals(className) && "execute".equals(matcher.group(4))) {
                                        // We are running a layer test with a modified-to-be-more-informative stacktrace
                                        lastWasLayerBuilderExecute = true;
                                    } else {
                                        var classBinaryName = className.replace('.', '/');
                                        var rest = classBinaryName.substring(0, classBinaryName.lastIndexOf('/'));
                                        var relative = rest + "/" + fileName + "." + extension;
                                        for (var root : potentialSourceRoots) {
                                            // We place an annotation at every matching path, as we don't know which one it is...

                                            var relativePath = codeLocation.resolve(root).resolve(relative);
                                            if (Files.exists(relativePath)) {
                                                var actualLineNumber = Integer.parseInt(lineNumber);
                                                if (lastWasLayerBuilderExecute) {
                                                    var sourceLines = Files.readAllLines(relativePath);
                                                    if (sourceLines.size() >= actualLineNumber) {
                                                        var lineValue = sourceLines.get(actualLineNumber - 1).trim();
                                                        if (lineValue.endsWith("\"\"\"")) {
                                                            // We only bother figuring out this particularly simple case
                                                            actualLineNumber += lastLineOffset;
                                                        }
                                                    }
                                                }

                                                var parts = new ArrayList<String>();
                                                Integer currentId = id;
                                                while (currentId != null) {
                                                    parts.add(eventNames.getOrDefault(currentId, "<unknown>"));
                                                    currentId = eventParents.get(currentId);
                                                }
                                                if (testingUtilsId != null) {
                                                    parts.add(testingUtilsId);
                                                }
                                                var message = status + ": " + String.join(" > ", parts.reversed());

                                                var relativePathString = codeLocation.relativize(relativePath).toString();
                                                var set = annotated.computeIfAbsent(relativePathString, k -> new HashSet<>());
                                                if (set.add(actualLineNumber + "::" + message)) {
                                                    System.out.println(
                                                        "::error file=" + relativePathString + ",line=" + actualLineNumber + "::" + escapeData(message)
                                                    );
                                                }
                                            }
                                        }
                                        if (lastWasLayerBuilderExecute) {
                                            lastWasLayerBuilderExecute = false;
                                        }
                                        lastLineOffset = Integer.parseInt(lineNumber);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static class TreeNode {
        private String text;
        private String status;
        private boolean container;
        private final List<TreeNode> children = new ArrayList<>();

        public int depth() {
            return children.stream().mapToInt(TreeNode::depth).max().orElse(0) + 1;
        }

        public static String toHtml(List<TreeNode> children) {
            var builder = new StringBuilder().append("<table>");
            for (var child : children) {
                builder.append("<tr><td>");
                if (child.container) {
                    builder.append("<strong>");
                }
                builder.append(child.text);
                if (child.container) {
                    builder.append("</strong>");
                }
                builder.append("</td><td>");
                if (child.status != null) {
                    builder.append(child.status);
                }
                builder.append("</td></tr>");
                for (var subChild : child.children) {
                    builder.append("<tr><td><ul>");
                    if (subChild.container) {
                        builder.append("<strong>");
                    }
                    builder.append(subChild.text);
                    if (subChild.container) {
                        builder.append("</strong>");
                    }
                    builder.append("</ul></td><td>");
                    if (subChild.status != null) {
                        builder.append(subChild.status);
                    }
                    builder.append("</td></tr>");
                }
            }
            builder.append("</table>");
            return builder.toString();
        }

        public String toHtml() {
            var depth = depth();
            if (depth > 2) {
                return "<details><summary>" + text + (status == null ? "" : " " + status) + "</summary>" + (depth > 3 ?
                    "<ul>"+children.stream().map(TreeNode::toHtml).collect(Collectors.joining()) + "</ul>" :
                    toHtml(children)
                ) + "</details>";
            } else {
                return toHtml(List.of(this));
            }
        }
    }

    @CommandLine.Command(name = "summary", description = "Process results into GitHub action annotations")
    public void simplehtml(
        @CommandLine.Parameters(index = "0", arity = "1", description = "Output file")
        Path output,
        @CommandLine.Parameters(index = "1..*", arity = "1..", description = "List of report files to process")
        List<Path> reports
    ) throws Exception {
        List<TreeNode> root = new ArrayList<>();
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
            String os = null;
            String javaVersion = null;

            var infrastructureNodes = getChildrenByNameNS(element, "infrastructure", Namespace.REPORTING_CORE.getUri());
            if (!infrastructureNodes.isEmpty()) {
                var node = infrastructureNodes.getFirst();
                var testingUtilsNodes = getChildrenByNameNS(node, "id", TESTINGUTILS.getUri());
                if (!testingUtilsNodes.isEmpty()) {
                    testingUtilsId = testingUtilsNodes.getFirst().getTextContent();
                }
                var osNode = getChildrenByNameNS(node, "operatingSystem", Namespace.REPORTING_CORE.getUri());
                if (!osNode.isEmpty()) {
                    os = getCharacterDataFromElement(osNode.getFirst());
                }
                var javaVersionNode = getChildrenByNameNS(node, "javaVersion", Namespace.REPORTING_JAVA.getUri());
                if (!javaVersionNode.isEmpty()) {
                    javaVersion = getCharacterDataFromElement(javaVersionNode.getFirst());
                }
            }

            Map<Integer, TreeNode> eventNodes = new HashMap<>();

            Map<Integer, String> eventNames = new HashMap<>();
            Map<Integer, Integer> eventParents = new HashMap<>();
            Set<Integer> eventIsContainer = new HashSet<>();
            List<TreeNode> rootNodes = new ArrayList<>();
            var started = getChildrenByNameNS(element, "started", Namespace.REPORTING_EVENTS.getUri());
            for (var event : started) {
                var id = Integer.parseInt(event.getAttribute("id"));
                var parentId = event.getAttribute("parentId");
                var node = eventNodes.computeIfAbsent(id, k -> new TreeNode());
                if (!parentId.isEmpty()) {
                    eventParents.put(id, Integer.parseInt(parentId));
                } else {
                    rootNodes.add(node);
                }
                var name = event.getAttribute("name");

                var metadatas = getChildrenByNameNS(event, "metadata", Namespace.REPORTING_CORE.getUri());
                if (!metadatas.isEmpty()) {
                    var metadata = metadatas.getFirst();
                    var junitTypes = getChildrenByNameNS(metadata, "type", JUNIT.getUri());
                    if (!junitTypes.isEmpty()) {
                        if ("CONTAINER".equals(junitTypes.getFirst().getTextContent())) {
                            eventIsContainer.add(id);
                        }
                    }
                }

                eventNames.put(id, name);
            }
            var finished = getChildrenByNameNS(element, "finished", Namespace.REPORTING_EVENTS.getUri());
            for (var event : finished) {
                var id = Integer.parseInt(event.getAttribute("id"));
                var results = getChildrenByNameNS(event, "result", Namespace.REPORTING_CORE.getUri());
                if (!results.isEmpty()) {
                    var result = results.getFirst();
                    var status = result.getAttribute("status");


                    var node = eventNodes.get(id);
                    node.container = eventIsContainer.contains(id);
                    switch (status) {
                        case "FAILED", "ERRORED", "ABORTED" -> node.status = "❌";
                        case "SUCCESSFUL" -> {
                            if (!node.container) {
                                node.status = "✅";
                            }
                        }
                        case "SKIPPED" -> node.status = "⏭️";
                        default -> node.status = "❓";
                    }
                    node.text = eventNames.get(id);
                    var parent = eventParents.get(id);
                    if (parent != null) {
                        var parentNode = eventNodes.get(parent);
                        if (parentNode != null) {
                            parentNode.children.add(node);
                        }
                    }
                }
            }
            var singleNode = new TreeNode();
            singleNode.container = true;
            if (testingUtilsId != null) {
                singleNode.text = testingUtilsId;
            } else {
                var directoryName = path.getParent().getFileName().toString();
                String arch = null;
                if (directoryName.contains("aarch64")) {
                    arch = "aarch64";
                } else if (directoryName.contains("x86_64")) {
                    arch = "x86_64";
                }
                var testBuilder = new StringBuilder();
                testBuilder.append(os == null ? "Unknown" : os);
                if (arch != null) {
                    testBuilder.append(" (").append(arch).append(")");
                }
                if (javaVersion != null) {
                    testBuilder.append(" - Java ").append(javaVersion);
                }
                singleNode.text = testBuilder.toString();
            }
            singleNode.children.addAll(rootNodes);
            root.add(singleNode);
        }
        var html = root.stream()
            .map(TreeNode::toHtml)
            .collect(Collectors.joining("\n"));
        Files.writeString(output, html);
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
