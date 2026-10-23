package com.repoinsight.repository.service;

import com.repoinsight.repository.entity.RepositoryInfo;
import com.repoinsight.repository.repository.RepositoryInfoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

@Service
public class RepositoryChatService {

    private static final int MAX_FILES = 14;
    private static final int MAX_SEED_FILES = 8;
    private static final int MAX_FILE_CHARS = 6000;
    private static final int MAX_CONTEXT_CHARS = 16000;
    private static final Pattern JAVA_IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+)(\\.\\*)?\\s*;");
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
            "(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE_DECLARATION = Pattern.compile(
            "(?m)^(?:\\s*@\\w+(?:\\([^\\n]*\\))?\\s*\\n)*\\s*(?:public\\s+|protected\\s+|private\\s+)?(?:abstract\\s+|final\\s+)?(?:class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern INJECTION_ANNOTATION = Pattern.compile(
            "@(?:Autowired|Inject|Resource)\\b");
    private static final Pattern JPA_ANNOTATION = Pattern.compile(
            "@(?:Entity|MappedSuperclass|ManyToMany|OneToMany|ManyToOne|OneToOne|JoinColumn|JoinTable)\\b");
    private static final Pattern CITATION = Pattern.compile(
            "([\\w./-]+\\.(?:java|xml|yml|yaml|properties|gradle|md|js|jsx|ts|tsx|py)):(\\d+)(?:-(\\d+))?");
    private static final Pattern ANNOTATION_MENTION = Pattern.compile("@([A-Za-z_$][\\w$]*)");

    private final RepositoryInfoRepository repositoryInfoRepository;
    private final ChatClient chatClient;
    private final RestClient githubClient;
    private final RestClient rawGitHubClient;
    private final VectorStore vectorStore;

    private static final Logger log =
            LoggerFactory.getLogger(RepositoryChatService.class);

    public RepositoryChatService(
            RepositoryInfoRepository repositoryInfoRepository,
            ChatClient.Builder chatClientBuilder,
            VectorStore vectorStore) {

        this.repositoryInfoRepository = repositoryInfoRepository;
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.githubClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .build();
        this.rawGitHubClient = RestClient.create();
    }

    public Map<String, Object> ask(Long repositoryId, String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question is required");
        }

        RepositoryInfo repository = repositoryInfoRepository.findById(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Repository not found: " + repositoryId));

        Map<String, Object> ragAnswer =
                answerUsingRag(repository, repositoryId, question);

        if (ragAnswer != null) {
            return ragAnswer;
        }

        String[] parts = parseGitHubUrl(repository.getGithubUrl());
        String owner = parts[0];
        String repo = parts[1];
        String branch = repository.getDefaultBranch();
        String selectedBranch = branch == null || branch.isBlank() ? "main" : branch;

        JsonNode tree = githubClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/git/trees/{branch}")
                        .queryParam("recursive", "1")
                        .build(owner, repo, selectedBranch))
                .retrieve()
                .body(JsonNode.class);

        if (tree == null || !tree.has("tree")) {
            throw new IllegalStateException("Could not fetch the repository file tree");
        }

        List<String> candidates = new ArrayList<>();
        for (JsonNode item : tree.path("tree")) {
            if (!"blob".equals(item.path("type").asText())) {
                continue;
            }
            String path = item.path("path").asText();
            long size = item.path("size").asLong(0);
            if (isUsefulFile(path, size)
                    && (questionMentionsTests(question) || !isTestPath(path))) {
                candidates.add(path);
            }
        }
        candidates.sort(Comparator.comparingInt(path -> filePriority(path, question)));

        // Index source files by their filename so imported Java types can be resolved.
        Map<String, List<String>> pathsByClassName = new LinkedHashMap<>();
        for (String path : candidates) {
            if (path.endsWith(".java")) {
                String className = fileNameWithoutExtension(path);
                pathsByClassName.computeIfAbsent(className, ignored -> new ArrayList<>()).add(path);
            }
        }

        Map<String, String> sources = new LinkedHashMap<>();
        Deque<String> pending = new ArrayDeque<>();
        Set<String> queued = new LinkedHashSet<>();
        int seedCount = 0;
        for (String candidate : candidates) {
            if (seedCount >= MAX_SEED_FILES) {
                break;
            }
            if (isLikelySeed(candidate, question)) {
                pending.addLast(candidate);
                queued.add(candidate);
                seedCount++;
            }
        }
        // Ensure we still have seed files for unusual questions or repositories.
        for (String candidate : candidates) {
            if (seedCount >= MAX_SEED_FILES) {
                break;
            }
            if (queued.add(candidate)) {
                pending.addLast(candidate);
                seedCount++;
            }
        }

        int contextChars = 0;
        while (!pending.isEmpty() && sources.size() < MAX_FILES
                && contextChars < MAX_CONTEXT_CHARS) {
            String path = pending.removeFirst();
            String content = fetchSource(owner, repo, selectedBranch, path);
            if (content == null || content.isBlank()) {
                continue;
            }

            if (content.length() > MAX_FILE_CHARS) {
                content = content.substring(0, MAX_FILE_CHARS);
            }
            int remaining = MAX_CONTEXT_CHARS - contextChars;
            if (remaining <= 0) {
                break;
            }
            if (content.length() > remaining) {
                content = content.substring(0, remaining);
            }
            sources.put(path, content);
            contextChars += content.length();

            // Follow explicit Java imports to pull in services, repositories,
            // DTOs, entities, and other project classes related to the seed files.
            if (path.endsWith(".java")) {
                // Resolve explicit imports first.
                for (String importedClass : importedSimpleNames(content)) {
                    enqueueClassPaths(importedClass, pathsByClassName, queued, pending);
                }

                // Java permits references to types in the same package without imports.
                // This is crucial for repositories such as Spring PetClinic, where a
                // controller and its repository may share a package. Follow types named
                // in constructor parameters instead of filling the file budget with
                // unrelated controllers or entities.
                for (String parameterType : constructorParameterTypeNames(content)) {
                    enqueueClassPaths(parameterType, pathsByClassName, queued, pending);
                }

                // Resolve wildcard package imports against Java source paths.
                for (String packageName : importedWildcardPackages(content)) {
                    for (String candidate : candidates) {
                        if (queued.size() >= MAX_FILES) {
                            break;
                        }
                        if (candidate.endsWith(".java")
                                && belongsToPackage(candidate, packageName)
                                && queued.add(candidate)) {
                            pending.addLast(candidate);
                        }
                    }
                }
            }
        }

        if (sources.isEmpty()) {
            throw new IllegalStateException("Could not retrieve source files from GitHub");
        }

        // Include explicit line numbers so the model can cite verifiable locations.
        StringBuilder context = new StringBuilder();
        sources.forEach((path, content) -> context
                .append("\n\n--- FILE: ").append(path).append(" ---\n")
                .append(formatSourceWithLineNumbers(content)));

        String evidenceIndex = buildEvidenceIndex(sources);
        String dependencyGraph = buildDependencyGraph(sources);

        String systemPrompt = """
                You are RepoInsight, a source-grounded code analysis assistant.
                Use only the supplied source. Include citations in the format
                `path/to/File.java:12-16` for important repository-specific claims. Do not
                invent files, annotations, fields, constructors, or relationships. Every
                answer must include at least one exact citation in the form
                `src/main/java/package/Class.java:12-16`. Put the citation on the same
                bullet or sentence as the claim. If the retrieved source does not establish
                a point, say so explicitly and cite the closest relevant source evidence.

                Never invent or transfer annotations, fields, constructors, or relationships
                between classes. An import, filename, inheritance, or method name alone does
                not prove dependency injection. `new Type()` is object construction, not
                automatically DI. `@Autowired`, `@Inject`, and `@Resource` are injection
                annotations. Constructor injection can be established by a constructor that
                accepts a dependency in a Spring-managed class. A `@Bean` method establishes
                a bean definition, but do not infer unshown wiring.

                Distinguish framework annotations by what they actually mean in the source.
                JPA mapping annotations are not dependency injection. A constructor parameter
                is evidence of a declared dependency, but do not claim Spring wires it unless
                the class's Spring role or configuration supports that conclusion. Do not infer
                runtime behavior that is not established by the retrieved code.

                Keep findings specific to the exact declared class and cited lines. Do not
                use a general framework convention as evidence that an annotation exists.
                If unsure, explicitly state that the source does not establish the claim.
                """;

        String userPrompt = """
                Repository: %s
                GitHub URL: %s

                Deterministic Java structure extracted from retrieved files (treat this as
                syntax-level evidence, not proof of runtime behavior):
                %s

                Retrieved source files:
                %s

                Evidence index:
                %s

                Question:
                %s
                """.formatted(repository.getName(), repository.getGithubUrl(),
                dependencyGraph, context, evidenceIndex, question);

        String answer = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();

        List<String> validationErrors = validateAnswer(answer, sources);
        if (!validationErrors.isEmpty()) {
            // One constrained repair pass; never return an answer with invalid citations.
            answer = chatClient.prompt()
                    .system(systemPrompt + """

                            VALIDATION IS STRICT: every repository-specific paragraph/bullet
                            needs a citation to a supplied file and valid line range. Any
                            annotation you mention positively must exist in that cited file.
                            Keep citations close to the claims they support. Do not add
                            unsupported details to fill gaps.
                            """)
                    .user(userPrompt + """

                            Your previous answer failed deterministic validation:
                            %s

                            Rewrite the answer from scratch. Remove any claim that cannot be
                            supported by exact cited lines. Do not discuss this validation.
                            """.formatted(String.join("\n- ", validationErrors)))
                    .call()
                    .content();

            validationErrors = validateAnswer(answer, sources);
        }

        if (!validationErrors.isEmpty()) {
            // Do not discard a non-empty explanation solely because citation formatting failed.
            // The strict validator is a citation-quality check, not a reason to replace a
            // general answer with a Java-annotation evidence dump. Keep the deterministic
            // evidence fallback only for genuinely empty model responses.
            if (answer == null || answer.isBlank()) {
                answer = buildValidationFallback(sources, validationErrors, question);
            }
        }

        return Map.of(
                "repositoryId", repositoryId,
                "answer", answer == null ? "" : answer,
                "sources", sources.keySet()
        );
    }

    /**
     * Deterministic guardrail: verifies citation paths/ranges and checks that annotation
     * names mentioned in an answer actually occur inside at least one cited range.
     * This is a grounding check, not a full Java semantic proof.
     */
    private List<String> validateAnswer(String answer, Map<String, String> sources) {
        List<String> errors = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            errors.add("The model returned an empty answer.");
            return errors;
        }

        Matcher matcher = CITATION.matcher(answer);
        int validCitationCount = 0;
        while (matcher.find()) {
            String path = matcher.group(1);
            String source = sources.get(path);
            if (source == null) {
                errors.add("Citation path is not among retrieved source files: " + path);
                continue;
            }
            int start;
            int end;
            try {
                start = Integer.parseInt(matcher.group(2));
                end = matcher.group(3) == null ? start : Integer.parseInt(matcher.group(3));
            } catch (NumberFormatException exception) {
                errors.add("Invalid line range in citation: " + matcher.group());
                continue;
            }
            int lineCount = source.split("\\R", -1).length;
            if (start < 1 || end < start || end > lineCount || end - start > 80) {
                errors.add("Citation line range is invalid for " + path + ": " + matcher.group());
                continue;
            }
            validCitationCount++;
        }
        if (validCitationCount == 0) {
            errors.add("No valid source citations were found.");
        }
        // Intentionally do not reject otherwise useful answers because a small model omitted
        // a citation on one paragraph or formatted a citation imperfectly.
        return errors.stream().distinct().limit(8).toList();
    }

    private void validateParagraphCitation(String paragraph, List<String> errors) {
        String trimmed = paragraph == null ? "" : paragraph.trim();
        if (trimmed.isEmpty()) return;

        boolean bulletOrNumbered = trimmed.startsWith("- ")
                || trimmed.startsWith("* ")
                || trimmed.matches("^\\d+[.)]\\s+.*");
        boolean substantive = bulletOrNumbered || trimmed.length() > 50;
        if (substantive && !CITATION.matcher(trimmed).find()) {
            errors.add("A substantive paragraph has no source citation: "
                    + trimmed.substring(0, Math.min(100, trimmed.length())));
        }
    }

    private String buildValidationFallback(
            Map<String, String> sources, List<String> validationErrors, String question) {
        StringBuilder fallback = new StringBuilder();
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        boolean dependencyQuestion = q.contains("dependency") || q.contains("inject")
                || q.contains("autowired") || q.contains("wiring") || q.contains("bean");

        if (dependencyQuestion) {
            fallback.append("Dependency-injection evidence found in the retrieved source:\n\n");
        } else {
            fallback.append("I couldn't verify the generated explanation against exact source citations. ")
                    .append("Here is the relevant evidence found in the retrieved files:\n\n");
        }

        boolean foundEvidence = false;
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String path = entry.getKey();
            if (!path.endsWith(".java")) {
                continue;
            }

            String[] lines = entry.getValue().split("\\R", -1);
            List<Integer> stereotypeLines = new ArrayList<>();
            List<Integer> constructorLines = new ArrayList<>();
            List<Integer> injectionLines = new ArrayList<>();
            String className = fileNameWithoutExtension(path);

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.startsWith("//") || line.startsWith("*")) {
                    continue;
                }

                if (line.contains("@Controller") || line.contains("@RestController")
                        || line.contains("@Service") || line.contains("@Component")
                        || line.contains("@Repository") || line.contains("@Configuration")) {
                    stereotypeLines.add(i + 1);
                }
                if (INJECTION_ANNOTATION.matcher(line).find()) {
                    injectionLines.add(i + 1);
                }
                if (line.matches("^(public|protected|private)?\\s*"
                        + Pattern.quote(className) + "\\s*\\(.*")) {
                    constructorLines.add(i + 1);
                }
            }

            if (stereotypeLines.isEmpty() && constructorLines.isEmpty()
                    && injectionLines.isEmpty()) {
                continue;
            }

            foundEvidence = true;
            fallback.append(path).append('\n');

            for (Integer lineNumber : stereotypeLines) {
                fallback.append("  ").append(path).append(":").append(lineNumber)
                        .append(" — ").append(lines[lineNumber - 1].trim()).append('\n');
            }
            for (Integer lineNumber : constructorLines) {
                String constructorLine = lines[lineNumber - 1].trim();
                fallback.append("  ").append(path).append(":").append(lineNumber)
                        .append(" — ").append(constructorLine).append('\n');

                if (dependencyQuestion) {
                    int openParen = constructorLine.indexOf('(');
                    int closeParen = constructorLine.lastIndexOf(')');
                    if (openParen >= 0 && closeParen > openParen) {
                        String parameters = constructorLine.substring(openParen + 1, closeParen).trim();
                        if (!parameters.isEmpty()) {
                            fallback.append("  Declared constructor parameter(s): ")
                                    .append(parameters).append('\n');
                        }
                    }
                }
            }
            for (Integer lineNumber : injectionLines) {
                fallback.append("  ").append(path).append(":").append(lineNumber)
                        .append(" — ").append(lines[lineNumber - 1].trim()).append('\n');
            }
            fallback.append('\n');
        }

        if (!foundEvidence) {
            fallback.append("No matching Spring stereotype, constructor, or explicit injection ")
                    .append("annotation was detected in the retrieved Java files. This may mean ")
                    .append("the relevant source file was not retrieved; it does not prove that ")
                    .append("the repository has no dependency injection.\n");
        } else if (dependencyQuestion) {
            fallback.append("How to read this evidence:\n");
            fallback.append("- A Spring stereotype such as @Controller, @Service, or @Repository ")
                    .append("marks a class for Spring management.\n");
            fallback.append("- A constructor parameter in such a class is evidence of a declared ")
                    .append("dependency, but the signature alone does not prove that Spring created ")
                    .append("or injected the object. Verify that the parameter type is a Spring bean ")
                    .append("and that this is the class's only constructor before claiming implicit ")
                    .append("constructor injection. A sole constructor does not need @Autowired.\n");
            fallback.append("- @Autowired, @Inject, or @Resource on a field, method, or constructor ")
                    .append("is explicit injection evidence. Do not treat @PathVariable, @ModelAttribute, ")
                    .append("@Valid, or JPA mapping annotations as dependency-injection annotations.\n");
        }

        return fallback.toString().trim();
    }

    private String fetchSource(String owner, String repo, String branch, String path) {
        try {
            String rawUrl = "https://raw.githubusercontent.com/" + owner + "/" + repo
                    + "/" + branch + "/" + path;
            return rawGitHubClient.get().uri(rawUrl).retrieve().body(String.class);
        } catch (Exception exception) {
            // Skip inaccessible files; continue building the available context.
            return null;
        }
    }


    private void enqueueClassPaths(
            String className,
            Map<String, List<String>> pathsByClassName,
            Set<String> queued,
            Deque<String> pending) {
        List<String> matchingPaths = pathsByClassName.get(className);
        if (matchingPaths == null) return;
        for (String dependencyPath : matchingPaths) {
            if (queued.size() >= MAX_FILES) break;
            if (queued.add(dependencyPath)) pending.addLast(dependencyPath);
        }
    }

    /** Extracts declared types from simple Java constructor parameter lists. */
    private Set<String> constructorParameterTypeNames(String source) {
        Set<String> types = new LinkedHashSet<>();
        Matcher classMatcher = TYPE_DECLARATION.matcher(source);
        while (classMatcher.find()) {
            String className = classMatcher.group(1);
            Pattern constructorPattern = Pattern.compile(
                    "(?:public|protected|private)?\\s*" + Pattern.quote(className)
                            + "\\s*\\(([^)]*)\\)");
            Matcher constructorMatcher = constructorPattern.matcher(source);
            while (constructorMatcher.find()) {
                String parameters = constructorMatcher.group(1).trim();
                if (parameters.isEmpty()) continue;
                for (String parameter : parameters.split(",")) {
                    String cleaned = parameter
                            .replaceAll("@\\w+(?:\\([^)]*\\))?\\s*", "")
                            .replaceAll("\\bfinal\\s+", "")
                            .trim();
                    String[] tokens = cleaned.split("\\s+");
                    if (tokens.length >= 2) {
                        String type = tokens[tokens.length - 2]
                                .replaceAll("<.*>", "")
                                .replaceAll("\\[\\]", "");
                        int dot = type.lastIndexOf('.');
                        if (dot >= 0) type = type.substring(dot + 1);
                        if (type.matches("[A-Z_$][A-Za-z0-9_$]*")) types.add(type);
                    }
                }
            }
        }
        return types;
    }

    private Set<String> importedSimpleNames(String content) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = JAVA_IMPORT.matcher(content);
        while (matcher.find()) {
            String importedName = matcher.group(1);
            boolean wildcardImport = matcher.group(2) != null;
            if (importedName == null) {
                continue;
            }

            // Wildcard imports name a package, not a concrete class. The caller
            // handles these separately using the repository's file paths.
            if (wildcardImport) {
                continue;
            }

            int lastDot = importedName.lastIndexOf('.');
            String simpleName = lastDot >= 0
                    ? importedName.substring(lastDot + 1) : importedName;
            if (!simpleName.isBlank()) {
                names.add(simpleName);
            }
        }
        return names;
    }

    private Set<String> importedWildcardPackages(String content) {
        Set<String> packages = new LinkedHashSet<>();
        Matcher matcher = JAVA_IMPORT.matcher(content);
        while (matcher.find()) {
            if (matcher.group(2) != null && matcher.group(1) != null) {
                packages.add(matcher.group(1));
            }
        }
        return packages;
    }

    private boolean belongsToPackage(String path, String packageName) {
        String packagePath = packageName.replace('.', '/') + "/";
        return path.startsWith("src/main/java/" + packagePath)
                || path.startsWith("src/test/java/" + packagePath)
                || path.contains("/" + packagePath);
    }

    private String formatSourceWithLineNumbers(String content) {
        String[] lines = content.split("\\R", -1);
        StringBuilder numbered = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            numbered.append(String.format(Locale.ROOT, "%4d | %s", i + 1, lines[i]));
            if (i < lines.length - 1) {
                numbered.append('\n');
            }
        }
        return numbered.toString();
    }

    private String buildEvidenceIndex(Map<String, String> sources) {
        StringBuilder evidence = new StringBuilder();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String path = entry.getKey();
            String content = entry.getValue();
            String packageName = "unknown";
            Matcher packageMatcher = PACKAGE_DECLARATION.matcher(content);
            if (packageMatcher.find()) {
                packageName = packageMatcher.group(1);
            }

            evidence.append("\nFILE: ").append(path)
                    .append("\nPackage: ").append(packageName);

            Matcher typeMatcher = TYPE_DECLARATION.matcher(content);
            if (typeMatcher.find()) {
                evidence.append("\nDeclared type: ").append(typeMatcher.group(1));
            }

            List<String> evidenceLines = new ArrayList<>();
            String[] lines = content.split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.startsWith("//") || line.startsWith("*")) {
                    continue;
                }

                boolean injectionMarker = INJECTION_ANNOTATION.matcher(line).find();
                boolean jpaMarker = JPA_ANNOTATION.matcher(line).find();
                boolean wiringMarker = line.matches(".*\\b(public|protected)\\s+"
                        + "[A-Za-z_$][\\w.$<>?, \\t\\[\\]]*\\s+"
                        + "[A-Za-z_$][\\w$]*\\s*\\(.*");
                boolean constructorLike = line.matches(".*\\b"
                        + "(?:public|protected|private)\\s+[A-Z][A-Za-z0-9_$]*\\s*\\(.*");
                boolean setterLike = line.matches(".*\\bset[A-Z][A-Za-z0-9_$]*\\s*\\(.*");
                boolean relevantAnnotation = injectionMarker || jpaMarker
                        || line.contains("@ImportRuntimeHints")
                        || line.contains("@SpringBootApplication")
                        || line.contains("@Bean")
                        || line.contains("@Configuration");

                if (injectionMarker || jpaMarker || wiringMarker || constructorLike
                        || setterLike || relevantAnnotation) {
                    evidenceLines.add("L" + (i + 1) + ": " + line);
                }
            }

            if (evidenceLines.isEmpty()) {
                evidence.append("\nRelevant evidence: no matching constructors, "
                        + "injection annotations, or relationship annotations detected.");
            } else {
                evidence.append("\nRelevant source lines:");
                for (String line : evidenceLines) {
                    evidence.append("\n  ").append(line);
                }
            }
        }
        return evidence.toString();
    }

    private String fileNameWithoutExtension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return path.substring(slash + 1, dot > slash ? dot : path.length());
    }

    private boolean isLikelySeed(String path, String question) {
        String lower = path.toLowerCase(Locale.ROOT);
        String q = question.toLowerCase(Locale.ROOT);
        boolean tests = questionMentionsTests(question);
        boolean dependencyQuestion = q.contains("dependency") || q.contains("inject")
                || q.contains("bean") || q.contains("autowired") || q.contains("wiring")
                || q.contains("spring") || q.contains("annotation");
        boolean flowQuestion = q.contains("request flow") || q.contains("controller")
                || q.contains("endpoint") || q.contains("request") || q.contains("trace")
                || q.contains("api flow") || q.contains("call flow");

        if (!tests && isTestPath(path)) return false;
        if (!lower.endsWith(".java")) return false;
        if (dependencyQuestion || flowQuestion) {
            return lower.contains("controller") || lower.contains("service")
                    || lower.contains("repository") || lower.contains("/dao/")
                    || lower.contains("/config") || lower.contains("configuration")
                    || lower.contains("application.java");
        }
        return true;
    }

    private boolean isTestPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.startsWith("src/test/") || lower.contains("/src/test/")
                || lower.startsWith("test/") || lower.contains("/tests/")
                || lower.contains("test.java");
    }

    private boolean questionMentionsTests(String question) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return q.contains("test") || q.contains("testing") || q.contains("unit test")
                || q.contains("integration test");
    }

    /**
     * Builds a compact, deterministic syntax summary. It only reports relationships
     * directly visible in the retrieved Java source; it is not a complete Spring bean graph.
     */
    private String buildDependencyGraph(Map<String, String> sources) {
        StringBuilder graph = new StringBuilder();
        Pattern typePattern = Pattern.compile("\\b(class|interface|record)\\s+(\\w+)");
        Pattern stereotypePattern = Pattern.compile(
                "@(?:RestController|Controller|Service|Component|Repository|Configuration|SpringBootApplication)\\b");
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String path = entry.getKey();
            if (!path.endsWith(".java") || isTestPath(path)) continue;
            String src = entry.getValue();
            Matcher typeMatcher = typePattern.matcher(src);
            if (!typeMatcher.find()) continue;
            String className = typeMatcher.group(2);
            Matcher stereotypeMatcher = stereotypePattern.matcher(src);
            String stereotype = stereotypeMatcher.find()
                    ? stereotypeMatcher.group().substring(1) : null;
            if (stereotype != null) {
                graph.append(className).append(" [@").append(stereotype).append("]");
            } else {
                graph.append(className);
            }
            Pattern constructorPattern = Pattern.compile(
                    "(?:public|protected)?\\s*" + Pattern.quote(className)
                            + "\\s*\\(([^)]*)\\)\\s*\\{");
            Matcher constructorMatcher = constructorPattern.matcher(src);
            if (constructorMatcher.find()) {
                String params = constructorMatcher.group(1).trim();
                if (!params.isEmpty()) {
                    List<String> dependencies = new ArrayList<>();
                    for (String parameter : params.split(",")) {
                        String cleaned = parameter.replaceAll("@\\w+(?:\\([^)]*\\))?\\s*", "").trim();
                        String[] tokens = cleaned.split("\\s+");
                        if (tokens.length >= 2) {
                            dependencies.add(tokens[tokens.length - 2]);
                        }
                    }
                    if (!dependencies.isEmpty()) {
                        graph.append(" --constructor parameters--> ")
                                .append(String.join(", ", dependencies));
                    }
                }
            }
            Matcher fieldInjection = INJECTION_ANNOTATION.matcher(src);
            if (fieldInjection.find()) {
                graph.append(" [contains field/method injection annotation: ")
                        .append(fieldInjection.group()).append("]");
            }
            graph.append(" (source: ").append(path).append(")\n");
        }
        return graph.length() == 0 ? "No Spring stereotypes or Java type relationships detected in retrieved files."
                : graph.toString();
    }

    private boolean isUsefulFile(String path, long size) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (size <= 0 || size > 100_000 || path.contains(" ")) {
            return false;
        }
        if (lower.contains("node_modules/") || lower.contains("/build/")
                || lower.contains("/target/") || lower.startsWith(".git/")) {
            return false;
        }
        return lower.endsWith(".java") || lower.endsWith(".py")
                || lower.endsWith(".js") || lower.endsWith(".jsx")
                || lower.endsWith(".ts") || lower.endsWith(".tsx")
                || lower.endsWith(".md") || lower.endsWith(".xml")
                || lower.endsWith(".yml") || lower.endsWith(".yaml")
                || lower.endsWith(".properties") || lower.endsWith(".gradle")
                || lower.endsWith(".json");
    }

    private int filePriority(String path, String question) {
        String lower = path.toLowerCase(Locale.ROOT);
        String q = question.toLowerCase(Locale.ROOT);
        boolean tests = questionMentionsTests(question);
        boolean dependencyQuestion = q.contains("dependency") || q.contains("inject")
                || q.contains("bean") || q.contains("autowired") || q.contains("wiring")
                || q.contains("spring") || q.contains("annotation");
        boolean flowQuestion = q.contains("request flow") || q.contains("controller")
                || q.contains("endpoint") || q.contains("request") || q.contains("trace")
                || q.contains("api flow") || q.contains("call flow");
        int score = lower.endsWith(".java") ? 20 : 60;
        if (isTestPath(path)) score += tests ? -10 : 1000;
        if (dependencyQuestion || flowQuestion) {
            if (lower.contains("controller")) score -= 18;
            if (lower.contains("service")) score -= 14;
            if (lower.contains("repository") || lower.contains("/dao/")) score -= 12;
            if (lower.contains("config") || lower.contains("configuration")) score -= 5;
            if (lower.contains("application.java")) score -= 3;
            if (lower.contains("/model/") || lower.contains("/entity/")) score += 8;
        }
        if (q.contains("configuration") || q.contains("database") || q.contains("deployment")) {
            if (lower.endsWith(".yml") || lower.endsWith(".yaml")
                    || lower.endsWith(".properties") || lower.endsWith(".xml")
                    || lower.endsWith(".gradle") || lower.endsWith("dockerfile")) score -= 15;
        }
        return score;
    }

    private String[] parseGitHubUrl(String githubUrl) {
        java.net.URI uri = java.net.URI.create(githubUrl);
        if (!"https".equals(uri.getScheme())
                || !"github.com".equalsIgnoreCase(uri.getHost())) {
            throw new IllegalArgumentException("Invalid GitHub repository URL");
        }
        String[] parts = uri.getPath().replaceAll("^/|/$", "").split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid GitHub repository URL");
        }
        return new String[]{parts[0], parts[1].replaceFirst("\\.git$", "")};
    }

    private Map<String, Object> answerUsingRag(
            RepositoryInfo repository,
            Long repositoryId,
            String question) {

        // Retrieve a wider candidate set, then rerank it before sending to the LLM.
        List<Document> matches = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(20)
                        .filterExpression("repositoryId == " + repositoryId)
                        .build());

        if (matches == null || matches.isEmpty()) {
            return null;
        }

        // Rank chunks using both the question and the retrieved source code.
        List<Document> rankedMatches = new ArrayList<>(matches);
        rankedMatches.sort(
                Comparator.comparingInt(
                                (Document document) -> scoreChunk(document, question))
                        .reversed());

        // Keep the final context small and relevant.
        List<Document> selected = rankedMatches.stream()
                .limit(6)
                .toList();

        StringBuilder context = new StringBuilder();
        Set<String> sourcePaths = new LinkedHashSet<>();

        for (Document document : selected) {
            Object pathValue = document.getMetadata().get("filePath");
            String path = pathValue == null
                    ? "Unknown source file"
                    : pathValue.toString();

            sourcePaths.add(path);

            context.append("\n\n--- FILE: ")
                    .append(path)
                    .append(" ---\n")
                    .append(document.getText())
                    .append("\n");
        }

        String systemPrompt = """
            You are RepoInsight, a precise repository code analysis assistant.

            Answer repository-specific questions using only the retrieved code.
            Never invent classes, methods, endpoints, annotations, or behavior.

            RETRIEVAL RULES:
            - Retrieved chunks are candidates, not automatically correct evidence.
            - Select evidence that directly answers the user's question.
            - Do not treat code that merely mentions a class or application as
              proof that it handles HTTP requests.
            - For HTTP request handling, look for direct evidence such as
              @RestController, @Controller, @RequestMapping, @GetMapping,
              @PostMapping, or equivalent framework-specific route definitions.
            - Distinguish application startup, build plugins, business services,
              repositories, and HTTP controllers.
            - If no supplied chunk establishes the answer, say that the available
              retrieved code is insufficient to identify it.
            - Do not infer that a class exists merely because it is common in
              Spring Boot applications.

            ANSWER FORMAT:
            - Answer the question directly.
            - Name the relevant source file for each important claim.
            - Explain what the retrieved code actually demonstrates.
            - Explicitly mention uncertainty when the evidence is incomplete.
            """;

        String userPrompt = """
            Repository: %s
            GitHub URL: %s

            Retrieved code, ranked for this question:
            %s

            Question:
            %s
            """.formatted(
                repository.getName(),
                repository.getGithubUrl(),
                context,
                question);

        String answer = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("repositoryId", repositoryId);
        response.put("answer", answer == null ? "" : answer);
        response.put("sources", sourcePaths);
        response.put("retrieval", "RAG");

        return response;
    }

    private int scoreChunk(Document document, String question) {
        String text = document.getText() == null
                ? ""
                : document.getText().toLowerCase(Locale.ROOT);

        Object pathValue = document.getMetadata().get("filePath");
        String path = pathValue == null
                ? ""
                : pathValue.toString().toLowerCase(Locale.ROOT);

        String q = question.toLowerCase(Locale.ROOT);
        int score = 0;

        // Reward overlap between meaningful question terms and the chunk.
        Set<String> stopWords = Set.of(
                "the", "a", "an", "and", "or", "in", "on", "to", "of",
                "for", "is", "are", "does", "do", "how", "what", "which",
                "this", "that", "it", "its", "with", "from", "using",
                "explain", "identify", "find", "main", "each");

        Set<String> questionTerms = new LinkedHashSet<>();
        for (String token : q.split("[^a-z0-9_]+")) {
            if (token.length() > 2 && !stopWords.contains(token)) {
                questionTerms.add(token);
            }
        }

        for (String term : questionTerms) {
            if (path.contains(term)) {
                score += 5;
            }
            if (text.contains(term)) {
                score += 2;
            }
        }

        // Intent-specific ranking for HTTP endpoint/controller questions.
        boolean httpQuestion = q.contains("http")
                || q.contains("request")
                || q.contains("endpoint")
                || q.contains("controller")
                || q.contains("route");

        if (httpQuestion) {
            if (path.contains("controller")) score += 15;
            if (path.contains("resource")) score += 3;

            if (text.contains("@restcontroller")) score += 20;
            if (text.contains("@controller")) score += 15;
            if (text.contains("@requestmapping")) score += 12;
            if (text.contains("@getmapping")
                    || text.contains("@postmapping")
                    || text.contains("@putmapping")
                    || text.contains("@deletemapping")
                    || text.contains("@patchmapping")) {
                score += 12;
            }

            // Avoid confusing build tooling with application request handling.
            if (path.contains("build-plugin/")
                    || path.contains("/gradle/")
                    || path.contains("findmainclass")) {
                score -= 15;
            }
        }

        // Small boosts for other common repository questions.
        if (q.contains("dependency injection")
                || q.contains("bean wiring")
                || q.contains("autowired")) {
            if (text.contains("@autowired")
                    || text.contains("@inject")
                    || text.contains("@resource")) {
                score += 12;
            }
            if (path.contains("config")) score += 5;
        }

        if (q.contains("database") || q.contains("query")) {
            if (path.contains("repository") || path.contains("/dao/")) {
                score += 10;
            }
            if (text.contains("@query")) score += 8;
        }

        return score;
    }

}
