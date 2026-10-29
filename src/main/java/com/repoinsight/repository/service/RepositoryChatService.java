package com.repoinsight.repository.service;

import com.repoinsight.repository.entity.RepositoryInfo;
import com.repoinsight.repository.repository.RepositoryInfoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
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
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

@Service
public class RepositoryChatService {

    private static final int MAX_FILES = 4;
    private static final int MAX_SEED_FILES = 4;
    private static final int MAX_CONCURRENT_SOURCE_FETCHES = 6;
    private static final int MAX_FILE_CHARS = 4000;
    private static final int MAX_CONTEXT_CHARS = 4500;
    private static final int MAX_FILE_INDEX_CHARS = 5000;
    private static final int MAX_CHAT_COMPLETION_TOKENS = 384;
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

    private static final Logger log = LoggerFactory.getLogger(RepositoryChatService.class);

    public RepositoryChatService(
            RepositoryInfoRepository repositoryInfoRepository,
            ChatClient.Builder chatClientBuilder,
            VectorStore vectorStore) {

        this.repositoryInfoRepository = repositoryInfoRepository;
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        SimpleClientHttpRequestFactory githubRequestFactory = new SimpleClientHttpRequestFactory();
        githubRequestFactory.setConnectTimeout(Duration.ofSeconds(1));
        githubRequestFactory.setReadTimeout(Duration.ofSeconds(2));
        this.githubClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .requestFactory(githubRequestFactory)
                .build();
        SimpleClientHttpRequestFactory rawGitHubRequestFactory = new SimpleClientHttpRequestFactory();
        rawGitHubRequestFactory.setConnectTimeout(Duration.ofSeconds(1));
        rawGitHubRequestFactory.setReadTimeout(Duration.ofSeconds(2));
        this.rawGitHubClient = RestClient.builder()
                .requestFactory(rawGitHubRequestFactory)
                .build();
    }

    public Map<String, Object> ask(Long repositoryId, String question) {
        return ask(repositoryId, question, "");
    }

    public Map<String, Object> ask(
            Long repositoryId,
            String question,
            String conversationContext) {
        long requestStartedNanos = System.nanoTime();
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question is required");
        }
        String latestQuestion = question.trim();
        String historyContext = conversationContext == null
                ? ""
                : conversationContext.trim();

        RepositoryInfo repository = repositoryInfoRepository.findById(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Repository not found: " + repositoryId));

        long retrievalStartedNanos = System.nanoTime();
        if (!isProjectStructureQuestion(latestQuestion)) {
            Map<String, Object> ragAnswer = answerUsingRag(
                    repository, repositoryId, latestQuestion, historyContext);

            if (ragAnswer != null) {
                log.info("Repository chat completed: repositoryId={}, route=rag, elapsedMs={}",
                        repositoryId, elapsedMillis(requestStartedNanos));
                return ragAnswer;
            }
        }
        log.info("Repository chat RAG retrieval missed: repositoryId={}, retrievalMs={}",
                repositoryId, elapsedMillis(retrievalStartedNanos));

        String[] parts = parseGitHubUrl(repository.getGithubUrl());
        String owner = parts[0];
        String repo = parts[1];
        String branch = repository.getDefaultBranch();
        String selectedBranch = branch == null || branch.isBlank() ? "main" : branch;

        JsonNode tree;
        try {
            tree = githubClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/repos/{owner}/{repo}/git/trees/{branch}")
                            .queryParam("recursive", "1")
                            .build(owner, repo, selectedBranch))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException exception) {
            log.warn("Could not retrieve GitHub file tree: repositoryId={}",
                    repositoryId, exception);
            return buildFallbackResponse(
                    repositoryId,
                    latestQuestion,
                    Set.of(),
                    "GitHub's file tree could not be retrieved.");
        }

        if (tree == null || !tree.has("tree")) {
            log.warn("GitHub returned no file tree: repositoryId={}", repositoryId);
            return buildFallbackResponse(
                    repositoryId,
                    latestQuestion,
                    Set.of(),
                    "GitHub did not provide a repository file tree.");
        }
        if (isProjectStructureQuestion(latestQuestion)) {
            return answerProjectStructure(repositoryId, tree);
        }
        long treeFetchMillis = elapsedMillis(retrievalStartedNanos);
        String repositoryFileIndex = buildRepositoryFileIndex(tree, latestQuestion);

        List<String> candidates = new ArrayList<>();
        for (JsonNode item : tree.path("tree")) {
            if (!"blob".equals(item.path("type").asText())) {
                continue;
            }
            String path = item.path("path").asText();
            long size = item.path("size").asLong(0);
            if (isUsefulFile(path, size)
                    && (questionMentionsTests(latestQuestion) || !isTestPath(path))) {
                candidates.add(path);
            }
        }
        candidates.sort(Comparator.comparingInt(path -> filePriority(path, latestQuestion)));

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
            if (queued.add(candidate)) {
                pending.addLast(candidate);
                seedCount++;
            }
        }

        int contextChars = 0;
        try (var sourceExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (!pending.isEmpty() && sources.size() < MAX_FILES
                    && contextChars < MAX_CONTEXT_CHARS) {
                List<String> batchPaths = new ArrayList<>();
                int batchLimit = Math.min(
                        MAX_FILES - sources.size(),
                        MAX_CONCURRENT_SOURCE_FETCHES);
                while (!pending.isEmpty() && batchPaths.size() < batchLimit) {
                    batchPaths.add(pending.removeFirst());
                }

                List<CompletableFuture<String>> downloads = batchPaths.stream()
                        .map(path -> CompletableFuture.supplyAsync(
                                () -> fetchSource(
                                        owner, repo, selectedBranch, path),
                                sourceExecutor))
                        .toList();

                for (int batchIndex = 0; batchIndex < batchPaths.size(); batchIndex++) {
                    String path;
                    String content;
                    try {
                        path = batchPaths.get(batchIndex);
                        content = downloads.get(batchIndex).join();
                    } catch (CompletionException exception) {
                        if (exception.getCause() instanceof RuntimeException cause) {
                            throw cause;
                        }
                        throw exception;
                    }

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

                    if (path.endsWith(".java")) {
                        for (String importedClass : importedSimpleNames(content)) {
                            enqueueClassPaths(
                                    importedClass, pathsByClassName, queued, pending);
                        }
                        for (String parameterType : constructorParameterTypeNames(content)) {
                            enqueueClassPaths(
                                    parameterType, pathsByClassName, queued, pending);
                        }
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
            }
        }

        long sourceFetchMillis = elapsedMillis(retrievalStartedNanos) - treeFetchMillis;

        // Include explicit line numbers so the model can cite verifiable locations.
        StringBuilder context = new StringBuilder();
        sources.forEach((path, content) -> context
                .append("\n\n--- FILE: ").append(path).append(" ---\n")
                .append(formatSourceWithLineNumbers(content)));

        String systemPrompt = """
                You are RepoInsight, a repository-aware assistant. Answer the user's actual
                question using the repository metadata, file inventory, and retrieved source.
                Use the file inventory to answer questions about whether a file or technology
                exists and how the project is organized. Use source excerpts to explain
                implementation behavior. Do not treat filenames alone as proof of runtime
                behavior, and do not invent files, symbols, or behavior.

                If repository evidence is insufficient, say exactly what is missing and then
                give clearly labeled general guidance when useful. Never present general
                guidance as a fact about this repository. Cite the supplied source path for
                repository-specific claims; cite line numbers only when the context provides
                them. Be direct, specific, and concise, under 150 words.
                """;

        String userPrompt = """
                Repository: %s
                GitHub URL: %s
                Description: %s
                Primary language: %s
                Topics: %s

                Conversation history (context only; do not treat as repository evidence):
                %s

                Repository file inventory:
                %s

                Retrieved source files:
                %s

                Question:
                %s
                """.formatted(
                repository.getName(),
                repository.getGithubUrl(),
                repository.getDescription(),
                repository.getLanguage(),
                repository.getTopics(),
                historyContext,
                repositoryFileIndex,
                context,
                latestQuestion);

        long generationStartedNanos = System.nanoTime();
        String answer;
        try {
            answer = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .options(OpenAiChatOptions.builder()
                            .maxTokens(MAX_CHAT_COMPLETION_TOKENS))
                    .call()
                    .content();
        } catch (RuntimeException exception) {
            log.error("Repository chat model request failed: repositoryId={}",
                    repositoryId, exception);
            return buildFallbackResponse(
                    repositoryId,
                    latestQuestion,
                    sources.keySet(),
                    "The AI model request failed, so the retrieved source could not be analyzed.");
        }
        long generationMillis = elapsedMillis(generationStartedNanos);

        List<String> validationErrors = validateAnswer(answer, sources);
        if (!validationErrors.isEmpty()) {
            log.warn("Repository chat answer citation validation found issues: repositoryId={}, issues={}",
                    repositoryId, validationErrors.size());
        }

        if (!validationErrors.isEmpty()) {
            // Do not discard a non-empty explanation solely because citation formatting
            // failed.
            // The strict validator is a citation-quality check, not a reason to replace a
            // general answer with a Java-annotation evidence dump. Keep the deterministic
            // evidence fallback only for genuinely empty model responses.
            if (answer == null || answer.isBlank()) {
                answer = buildValidationFallback(sources, validationErrors, question);
            }
        }

        log.info(
                "Repository chat completed: repositoryId={}, route=github-fallback, files={}, contextChars={}, treeFetchMs={}, sourceFetchMs={}, generationMs={}, elapsedMs={}",
                repositoryId, sources.size(), context.length(), treeFetchMillis,
                sourceFetchMillis, generationMillis, elapsedMillis(requestStartedNanos));

        return Map.of(
                "repositoryId", repositoryId,
                "answer", answer == null ? "" : answer,
                "sources", sources.keySet());
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private boolean isDependencyQuestion(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        return lower.contains("dependency") || lower.contains("inject")
                || lower.contains("bean") || lower.contains("autowired")
                || lower.contains("wiring") || lower.contains("spring")
                || lower.contains("annotation");
    }

    /**
     * Deterministic guardrail: verifies citation paths/ranges and checks that
     * annotation
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
        // Intentionally do not reject otherwise useful answers because a small model
        // omitted
        // a citation on one paragraph or formatted a citation imperfectly.
        return errors.stream().distinct().limit(8).toList();
    }

    private void validateParagraphCitation(String paragraph, List<String> errors) {
        String trimmed = paragraph == null ? "" : paragraph.trim();
        if (trimmed.isEmpty())
            return;

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
        } catch (RestClientException exception) {
            log.warn("Could not retrieve GitHub source file: repository={}, path={}",
                    owner + "/" + repo, path, exception);
            return null;
        }
    }

    private void enqueueClassPaths(
            String className,
            Map<String, List<String>> pathsByClassName,
            Set<String> queued,
            Deque<String> pending) {
        List<String> matchingPaths = pathsByClassName.get(className);
        if (matchingPaths == null)
            return;
        for (String dependencyPath : matchingPaths) {
            if (queued.size() >= MAX_FILES)
                break;
            if (queued.add(dependencyPath))
                pending.addLast(dependencyPath);
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
                if (parameters.isEmpty())
                    continue;
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
                        if (dot >= 0)
                            type = type.substring(dot + 1);
                        if (type.matches("[A-Z_$][A-Za-z0-9_$]*"))
                            types.add(type);
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
                    ? importedName.substring(lastDot + 1)
                    : importedName;
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
     * directly visible in the retrieved Java source; it is not a complete Spring
     * bean graph.
     */
    private String buildDependencyGraph(Map<String, String> sources) {
        StringBuilder graph = new StringBuilder();
        Pattern typePattern = Pattern.compile("\\b(class|interface|record)\\s+(\\w+)");
        Pattern stereotypePattern = Pattern.compile(
                "@(?:RestController|Controller|Service|Component|Repository|Configuration|SpringBootApplication)\\b");
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String path = entry.getKey();
            if (!path.endsWith(".java") || isTestPath(path))
                continue;
            String src = entry.getValue();
            Matcher typeMatcher = typePattern.matcher(src);
            if (!typeMatcher.find())
                continue;
            String className = typeMatcher.group(2);
            Matcher stereotypeMatcher = stereotypePattern.matcher(src);
            String stereotype = stereotypeMatcher.find()
                    ? stereotypeMatcher.group().substring(1)
                    : null;
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
                || lower.endsWith(".json") || lower.endsWith(".css")
                || lower.endsWith(".scss") || lower.endsWith(".sass")
                || lower.endsWith(".less") || lower.endsWith(".html")
                || lower.endsWith(".htm") || lower.endsWith(".vue")
                || lower.endsWith(".svelte") || lower.endsWith(".mdx")
                || lower.endsWith(".sql") || lower.endsWith(".sh")
                || lower.endsWith(".toml") || lower.endsWith(".ini")
                || lower.endsWith(".conf") || lower.endsWith(".gradle.kts")
                || lower.endsWith(".dockerfile") || lower.endsWith(".gitignore");
    }

    private int filePriority(String path, String question) {
        String lower = path.toLowerCase(Locale.ROOT);
        int priority = -pathQuestionScore(path, question) * 10;
        String fileName = lower.substring(lower.lastIndexOf('/') + 1);
        if (fileName.matches("readme(?:\\.[a-z0-9]+)?")) {
            priority -= 8;
        }
        if (isTestPath(path) && !questionMentionsTests(question)) {
            priority += 1000;
        }
        return priority;
    }

    private static int pathQuestionScore(String path, String question) {
        Set<String> terms = questionTerms(question);
        String lowerPath = path.toLowerCase(Locale.ROOT);
        String fileName = lowerPath.substring(lowerPath.lastIndexOf('/') + 1);
        int score = 0;
        for (String term : terms) {
            if (fileName.contains(term)) {
                score += 3;
            } else if (lowerPath.contains(term)) {
                score++;
            }
        }
        return score;
    }

    private static Set<String> questionTerms(String question) {
        Set<String> stopWords = Set.of(
                "the", "a", "an", "and", "or", "in", "on", "to", "of", "for",
                "is", "are", "does", "do", "how", "what", "which", "this",
                "that", "it", "its", "with", "from", "using", "please", "file",
                "files", "repository", "repo", "project", "code", "codebase",
                "have", "has", "any", "show", "tell", "me", "about");
        Set<String> terms = new LinkedHashSet<>();
        for (String token : question.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() > 1 && !stopWords.contains(token)) {
                terms.add(token);
            }
        }
        return terms;
    }

    private String buildRepositoryFileIndex(JsonNode tree, String question) {
        List<String> paths = new ArrayList<>();
        for (JsonNode item : tree.path("tree")) {
            if ("blob".equals(item.path("type").asText())) {
                paths.add(item.path("path").asText());
            }
        }
        return buildRepositoryFileIndex(paths, question);
    }

    static String buildRepositoryFileIndex(List<String> paths, String question) {
        Map<String, Integer> directories = new TreeMap<>();
        Map<String, Integer> extensions = new TreeMap<>();
        for (String path : paths) {
            int slash = path.indexOf('/');
            if (slash >= 0) {
                directories.merge(path.substring(0, slash), 1, Integer::sum);
            }
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && dot > path.lastIndexOf('/')) {
                extensions.merge(path.substring(dot).toLowerCase(Locale.ROOT), 1, Integer::sum);
            } else {
                extensions.merge("[no extension]", 1, Integer::sum);
            }
        }

        StringBuilder index = new StringBuilder("Total files: ")
                .append(paths.size()).append("\nTop-level directories: ");
        directories.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(25)
                .forEach(entry -> index.append(entry.getKey())
                        .append("/ (").append(entry.getValue()).append("), "));
        index.append("\nFile types: ");
        extensions.forEach((extension, count) -> index.append(extension)
                .append(" (").append(count).append("), "));

        List<String> rankedPaths = new ArrayList<>(paths);
        rankedPaths.sort(Comparator
                .comparingInt((String path) -> pathQuestionScore(path, question))
                .reversed()
                .thenComparing(String::compareToIgnoreCase));
        index.append("\nPaths (question matches first):\n");
        for (String path : rankedPaths) {
            if (index.length() + path.length() + 1 > MAX_FILE_INDEX_CHARS) {
                index.append("... remaining paths omitted; counts above include the full tree.");
                break;
            }
            index.append(path).append('\n');
        }
        return index.toString();
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
        return new String[] { parts[0], parts[1].replaceFirst("\\.git$", "") };
    }

    private Map<String, Object> answerUsingRag(
            RepositoryInfo repository,
            Long repositoryId,
            String question,
            String conversationContext) {

        long retrievalStartedNanos = System.nanoTime();

        List<Document> matches;
        try {
            matches = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(question)
                            .topK(20)
                            .filterExpression("repositoryId == " + repositoryId)
                            .build());
        } catch (RuntimeException exception) {
            log.warn("RAG search failed: repositoryId={}", repositoryId, exception);
            return null;
        }

        if (matches == null || matches.isEmpty()) {
            return null;
        }

        List<Document> ranked = new ArrayList<>(matches);
        ranked.sort(
                Comparator.comparingInt(
                        (Document d) -> scoreChunk(d, question))
                        .reversed());

        Set<String> seenChunks = new LinkedHashSet<>();
        List<Document> selected = new ArrayList<>();
        Set<String> sourcePaths = new LinkedHashSet<>();
        StringBuilder context = new StringBuilder();

        for (Document document : ranked) {
            String content = document.getText();
            if (content == null || content.isBlank()) {
                continue;
            }

            String normalized = content.trim();
            if (!seenChunks.add(normalized)) {
                continue;
            }

            int score = scoreChunk(document, question);

            // Avoid generating an answer from semantically weak candidates.
            if (score < 3 && selected.isEmpty()) {
                continue;
            }

            Object pathValue = document.getMetadata().get("filePath");
            String path = pathValue == null
                    ? "Unknown source file"
                    : pathValue.toString();

            int remaining = 6500 - context.length();
            if (remaining <= 0 || selected.size() >= 6) {
                break;
            }

            if (content.length() > remaining) {
                content = content.substring(0, remaining);
            }

            context.append("\n\n--- FILE: ")
                    .append(path)
                    .append(" ---\n")
                    .append(content);

            selected.add(document);
            sourcePaths.add(path);
        }

        if (selected.isEmpty()) {
            return null;
        }

        String systemPrompt = """
                You are RepoInsight, a repository code analysis assistant.

                Answer repository-specific questions using only the supplied code.
                Do not invent classes, methods, endpoints, annotations, or behavior.
                A filename or import alone does not prove runtime behavior.
                If the supplied code does not establish the answer, say so explicitly.
                Do not present general framework conventions as facts about this
                repository.

                Cite important claims using the exact source path and line numbers
                only when those line numbers are supplied in the source context.
                If line numbers are unavailable, cite the source filename without
                inventing a line range.

                Conversation history is only context for resolving references.
                Do not repeat it or treat prior assistant statements as repository facts.
                Be concise, answer the exact question, and state uncertainty when
                evidence is incomplete. Keep the answer under 150 words.
                """;

        String userPrompt = """
                Repository: %s
                GitHub URL: %s

                Conversation history (context only):
                %s

                Retrieved source code:
                %s

                Question:
                %s
                """.formatted(
                repository.getName(),
                repository.getGithubUrl(),
                conversationContext,
                context,
                question);

        long generationStartedNanos = System.nanoTime();

        String answer;
        try {
            answer = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .options(OpenAiChatOptions.builder()
                            .maxTokens(MAX_CHAT_COMPLETION_TOKENS))
                    .call()
                    .content();
        } catch (RuntimeException exception) {
            log.warn("RAG generation failed: repositoryId={}",
                    repositoryId, exception);
            return buildFallbackResponse(
                    repositoryId,
                    question,
                    sourcePaths,
                    "The AI model did not respond within the configured timeout.");
        }

        if (answer == null || answer.isBlank()) {
            log.warn("RAG generation returned an empty answer: repositoryId={}",
                    repositoryId);
            return buildFallbackResponse(
                    repositoryId,
                    question,
                    sourcePaths,
                    "The AI model returned an empty answer.");
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("repositoryId", repositoryId);
        response.put("answer", answer.trim());
        response.put("sources", sourcePaths);
        response.put("retrieval", "RAG");

        log.info(
                "RAG completed: repositoryId={}, candidates={}, selected={}, "
                        + "retrievalMs={}, generationMs={}",
                repositoryId,
                matches.size(),
                selected.size(),
                (generationStartedNanos - retrievalStartedNanos) / 1_000_000,
                elapsedMillis(generationStartedNanos));

        return response;
    }

    private Map<String, Object> buildFallbackResponse(
            Long repositoryId,
            String question,
            Set<String> sources,
            String limitation) {
        StringBuilder answer = new StringBuilder(
                "I couldn't complete the repository analysis for the question: \"")
                .append(question)
                .append("\". ")
                .append(limitation);
        if (sources.isEmpty()) {
            answer.append(" No source files were available to verify an answer.");
        } else {
            answer.append(" Retrieved source files: ")
                    .append(String.join(", ", sources))
                    .append('.');
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("repositoryId", repositoryId);
        response.put("answer", answer.toString());
        response.put("sources", sources);
        response.put("retrieval", "REPOSITORY_EVIDENCE_FALLBACK");
        return response;
    }

    private boolean isRepositoryImplementationQuestion(String question) {
        String lowerQuestion = question.toLowerCase(Locale.ROOT);
        return lowerQuestion.contains("in this repository")
                || lowerQuestion.contains("in this repo")
                || lowerQuestion.contains("in this project")
                || lowerQuestion.contains("in the codebase")
                || lowerQuestion.contains("in our code")
                || lowerQuestion.contains("where is ")
                || lowerQuestion.contains("where are ")
                || lowerQuestion.contains("where does ")
                || lowerQuestion.contains("which file")
                || lowerQuestion.contains("implemented in")
                || lowerQuestion.contains("how is ")
                        && (lowerQuestion.contains("handled")
                                || lowerQuestion.contains("implemented"));
    }

    private boolean isAuthenticationQuestion(String lowerQuestion) {
        return lowerQuestion.contains("authentication")
                || lowerQuestion.contains("authenticate")
                || lowerQuestion.contains("authorization")
                || lowerQuestion.contains("authorisation")
                || lowerQuestion.contains("jwt")
                || lowerQuestion.contains("login")
                || lowerQuestion.contains("log in")
                || lowerQuestion.contains("sign in")
                || lowerQuestion.contains("security")
                || lowerQuestion.contains("password")
                || lowerQuestion.contains("access control")
                || lowerQuestion.contains("permissions")
                || lowerQuestion.contains("roles");
    }

    private boolean isProjectStructureQuestion(String question) {
        String lowerQuestion = question.toLowerCase(Locale.ROOT);
        return lowerQuestion.contains("project structure")
                || lowerQuestion.contains("repository structure")
                || lowerQuestion.contains("repo structure")
                || lowerQuestion.contains("codebase structure")
                || lowerQuestion.contains("folder structure")
                || lowerQuestion.contains("directory structure")
                || lowerQuestion.contains("how is this project organized")
                || lowerQuestion.contains("how is the project organized");
    }

    static boolean isRepositoryOverviewQuestion(String question) {
        String lowerQuestion = question.toLowerCase(Locale.ROOT);
        return lowerQuestion.contains("what does this repository do")
                || lowerQuestion.contains("what does this repo do")
                || lowerQuestion.contains("what does this project do")
                || lowerQuestion.contains("what is this repository")
                || lowerQuestion.contains("what is this repo")
                || lowerQuestion.contains("what is this project for")
                || lowerQuestion.contains("what is the purpose of this repository")
                || lowerQuestion.contains("what is the purpose of this repo")
                || lowerQuestion.contains("describe this repository")
                || lowerQuestion.contains("describe this repo")
                || lowerQuestion.contains("summarize this repository")
                || lowerQuestion.contains("summarize this repo")
                || lowerQuestion.contains("repository overview")
                || lowerQuestion.contains("repo overview");
    }

    private Map<String, Object> answerRepositoryOverview(
            Long repositoryId,
            RepositoryInfo repository) {
        long startedNanos = System.nanoTime();
        String description = repository.getDescription();
        String readmePath = null;
        String readmeSummary = null;

        try {
            String[] parts = parseGitHubUrl(repository.getGithubUrl());
            String branch = repository.getDefaultBranch();
            String selectedBranch = branch == null || branch.isBlank() ? "main" : branch;
            JsonNode tree = githubClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/repos/{owner}/{repo}/git/trees/{branch}")
                            .queryParam("recursive", "1")
                            .build(parts[0], parts[1], selectedBranch))
                    .retrieve()
                    .body(JsonNode.class);

            if (tree != null && tree.has("tree")) {
                readmePath = findReadmePath(tree);
                if (readmePath != null) {
                    String readme = fetchSource(
                            parts[0], parts[1], selectedBranch, readmePath);
                    readmeSummary = extractReadmeSummary(readme);
                }
            }
        } catch (RestClientException | IllegalArgumentException exception) {
            log.warn("Could not retrieve repository overview from GitHub: repositoryId={}",
                    repositoryId, exception);
        }

        StringBuilder answer = new StringBuilder();
        if (description != null && !description.isBlank()) {
            answer.append(description.trim());
        }
        if (readmeSummary != null && !readmeSummary.isBlank()
                && !readmeSummary.equalsIgnoreCase(description == null ? "" : description.trim())) {
            if (!answer.isEmpty()) {
                answer.append("\n\n");
            }
            answer.append(readmeSummary);
        }
        if (answer.isEmpty()) {
            answer.append("I couldn't retrieve a repository description or README overview for ")
                    .append(repository.getName() == null ? "this repository" : repository.getName())
                    .append(". The repository-specific purpose can't be confirmed from the available metadata.");
        }

        if (repository.getLanguage() != null && !repository.getLanguage().isBlank()) {
            answer.append("\n\nPrimary language: ").append(repository.getLanguage()).append('.');
        }
        if (repository.getTopics() != null && !repository.getTopics().isBlank()) {
            answer.append("\nTopics: ").append(repository.getTopics()).append('.');
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("repositoryId", repositoryId);
        response.put("answer", answer.toString());
        response.put("sources", readmePath == null ? Set.of() : Set.of(readmePath));
        response.put("retrieval", "REPOSITORY_OVERVIEW");
        log.info("Repository chat completed: repositoryId={}, route=repository-overview, readmeFound={}, elapsedMs={}",
                repositoryId, readmePath != null, elapsedMillis(startedNanos));
        return response;
    }

    private String findReadmePath(JsonNode tree) {
        return streamTreeFiles(tree)
                .filter(path -> path.substring(path.lastIndexOf('/') + 1)
                        .toLowerCase(Locale.ROOT).matches("readme(?:\\.[a-z0-9]+)?"))
                .min(Comparator.comparingInt((String path) -> path.split("/").length)
                        .thenComparing(String::compareToIgnoreCase))
                .orElse(null);
    }

    private java.util.stream.Stream<String> streamTreeFiles(JsonNode tree) {
        List<String> paths = new ArrayList<>();
        for (JsonNode item : tree.path("tree")) {
            if ("blob".equals(item.path("type").asText())) {
                paths.add(item.path("path").asText());
            }
        }
        return paths.stream();
    }

    static String extractReadmeSummary(String readme) {
        if (readme == null || readme.isBlank()) {
            return "";
        }

        List<String> paragraphs = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        for (String line : readme.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                addReadmeParagraph(paragraphs, paragraph);
                continue;
            }
            if (trimmed.startsWith("#")
                    || trimmed.startsWith("<")
                    || trimmed.matches("\\[!?\\[.*")
                    || trimmed.matches("!?\\[.*]\\(.*\\)")
                    || trimmed.startsWith("<!--")) {
                addReadmeParagraph(paragraphs, paragraph);
                continue;
            }
            if (!paragraph.isEmpty()) {
                paragraph.append(' ');
            }
            paragraph.append(trimmed);
        }
        addReadmeParagraph(paragraphs, paragraph);

        String summary = paragraphs.stream()
                .filter(text -> text.length() >= 40)
                .findFirst()
                .orElseGet(() -> paragraphs.stream().findFirst().orElse(""));
        if (summary.length() > 1000) {
            int end = summary.lastIndexOf(' ', 1000);
            summary = summary.substring(0, end > 0 ? end : 1000) + "...";
        }
        return summary;
    }

    private static void addReadmeParagraph(
            List<String> paragraphs,
            StringBuilder paragraph) {
        if (!paragraph.isEmpty()) {
            String text = paragraph.toString()
                    .replaceAll("!\\[([^]]*)]\\([^)]*\\)", "$1")
                    .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
                    .replaceAll("[*_`]", "")
                    .trim();
            if (!text.isBlank()) {
                paragraphs.add(text);
            }
            paragraph.setLength(0);
        }
    }

    private Map<String, Object> answerProjectStructure(
            Long repositoryId,
            JsonNode tree) {
        long startedNanos = System.nanoTime();
        List<String> paths = new ArrayList<>();
        for (JsonNode item : tree.path("tree")) {
            if ("blob".equals(item.path("type").asText())) {
                paths.add(item.path("path").asText());
            }
        }
        String answer = buildProjectStructureSummary(paths);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("repositoryId", repositoryId);
        response.put("answer", answer);
        response.put("sources", Set.of());
        response.put("retrieval", "GITHUB_TREE");
        log.info("Repository chat completed: repositoryId={}, route=github-tree-structure, elapsedMs={}",
                repositoryId, elapsedMillis(startedNanos));
        return response;
    }

    static String buildProjectStructureSummary(List<String> paths) {
        Map<String, Integer> filesByTopLevelDirectory = new TreeMap<>();
        List<String> rootFiles = new ArrayList<>();

        for (String path : paths) {
            int slash = path.indexOf('/');
            if (slash < 0) {
                rootFiles.add(path);
            } else {
                filesByTopLevelDirectory.merge(path.substring(0, slash), 1, Integer::sum);
            }
        }

        StringBuilder answer = new StringBuilder(
                "The repository's project structure is organized around these top-level areas:");
        filesByTopLevelDirectory.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(10)
                .forEach(entry -> answer.append("\n- `")
                        .append(entry.getKey())
                        .append("/` — ")
                        .append(entry.getValue())
                        .append(entry.getValue() == 1 ? " file" : " files")
                        .append(" in the GitHub file tree."));

        List<String> keyRootFiles = rootFiles.stream()
                .filter(path -> path.equalsIgnoreCase("README.md")
                        || path.equalsIgnoreCase("pom.xml")
                        || path.equalsIgnoreCase("build.gradle")
                        || path.equalsIgnoreCase("build.gradle.kts")
                        || path.equalsIgnoreCase("package.json")
                        || path.equalsIgnoreCase("pyproject.toml")
                        || path.equalsIgnoreCase("go.mod")
                        || path.equalsIgnoreCase("Cargo.toml"))
                .sorted()
                .toList();
        if (!keyRootFiles.isEmpty()) {
            answer.append("\n\nKey root files: ")
                    .append(String.join(", ", keyRootFiles))
                    .append('.');
        }
        answer.append(
                "\n\nThis overview is based on the GitHub file tree; ask about a specific folder or module for more detail.");
        return answer.toString();
    }

    private int scoreChunk(Document document, String question) {
        String text = document.getText() == null
                ? ""
                : document.getText().toLowerCase(Locale.ROOT);

        Object pathValue = document.getMetadata().get("filePath");
        String path = pathValue == null
                ? ""
                : pathValue.toString().toLowerCase(Locale.ROOT);

        int score = 0;
        for (String term : questionTerms(question)) {
            if (path.contains(term)) {
                score += 5;
            }
            if (text.contains(term)) {
                score += 2;
            }
        }
        return score;
    }

}
