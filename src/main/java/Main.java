import java.util.Scanner;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.*;

public class Main {

    private static final Set<String> BUILTINS = Set.of("echo", "exit", "type", "pwd", "cd", "jobs");

    static class Job {
        int jobId;
        long pid;
        Process process;
        String commandLine;
    }

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        Path currentDirectory = Path.of(System.getProperty("user.dir"));

        List<Job> backgroundJobs = new ArrayList<>();

        while (true) {

            reapBackgroundJobs(backgroundJobs);

            System.out.print("$ ");

            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;

            if (input.equals("exit")) {
                break;
            }

            List<String> parsed = parseCommand(input);

            // Check for pipeline first - this must take precedence
            int pipeIndex = -1;
            for (int i = 0; i < parsed.size(); i++) {
                if (parsed.get(i).equals("|")) {
                    pipeIndex = i;
                    break;
                }
            }

            if (pipeIndex != -1) {
                handlePipeline(parsed, pipeIndex, currentDirectory);
                continue;
            }

            // Handle background job marker
            boolean isBackground = false;
            if (!parsed.isEmpty() && parsed.get(parsed.size() - 1).equals("&")) {
                isBackground = true;
                parsed.remove(parsed.size() - 1);
            }

            if (parsed.isEmpty()) continue;

            String command = parsed.get(0);

            // Special handling for top-level echo without pipe
            if ("echo".equals(command) && parsed.size() > 1) {
                int redirectIndex = -1;
                for (int i = 0; i < parsed.size(); i++) {
                    if (parsed.get(i).equals(">") || parsed.get(i).equals("1>") ||
                        parsed.get(i).equals("2>") || parsed.get(i).equals("2>>") ||
                        parsed.get(i).equals(">>") || parsed.get(i).equals("1>>")) {
                        redirectIndex = i;
                        break;
                    }
                }

                StringBuilder output = new StringBuilder();
                int end = (redirectIndex == -1) ? parsed.size() : redirectIndex;
                for (int i = 1; i < end; i++) {
                    if (i > 1) output.append(" ");
                    output.append(parsed.get(i));
                }

                if (redirectIndex != -1) {
                    String operator = parsed.get(redirectIndex);
                    String fileName = parsed.get(redirectIndex + 1);
                    Path filePath = Path.of(fileName);
                    Files.createDirectories(filePath.toAbsolutePath().getParent());

                    if (operator.equals("2>") || operator.equals("2>>")) {
                        Files.writeString(filePath, "", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                        System.out.println(output);
                    } else if (operator.equals(">") || operator.equals("1>")) {
                        Files.writeString(filePath, output.toString() + System.lineSeparator());
                    } else if (operator.equals(">>") || operator.equals("1>>")) {
                        Files.writeString(filePath, output.toString() + System.lineSeparator(),
                                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    }
                } else {
                    System.out.println(output);
                }
                continue;
            }

            // Other built-ins
            if (BUILTINS.contains(command)) {
                try {
                    executeBuiltin(parsed, null, System.out, currentDirectory);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                continue;
            }

            // External command
            int redirectIndex = -1;
            for (int i = 0; i < parsed.size(); i++) {
                if (parsed.get(i).equals(">") || parsed.get(i).equals("2>>") ||
                    parsed.get(i).equals("1>") || parsed.get(i).equals("2>") ||
                    parsed.get(i).equals(">>") || parsed.get(i).equals("1>>")) {
                    redirectIndex = i;
                    break;
                }
            }

            List<String> commandArgs = new ArrayList<>();
            for (int i = 0; i < parsed.size(); i++) {
                if (i == redirectIndex) break;
                commandArgs.add(parsed.get(i));
            }

            File executable = findExecutable(command);

            if (executable != null) {
                ProcessBuilder pb = new ProcessBuilder(commandArgs);
                pb.directory(currentDirectory.toFile());

                if (redirectIndex != -1) {
                    String operator = parsed.get(redirectIndex);
                    String fileName = parsed.get(redirectIndex + 1);
                    File outFile = new File(fileName);

                    if (operator.equals("2>")) {
                        pb.redirectError(outFile);
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    } else if (operator.equals("2>>")) {
                        pb.redirectError(ProcessBuilder.Redirect.appendTo(outFile));
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    } else if (operator.equals(">") || operator.equals("1>")) {
                        pb.redirectOutput(outFile);
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    } else if (operator.equals(">>") || operator.equals("1>>")) {
                        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(outFile));
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }
                } else {
                    pb.inheritIO();
                }

                Process process = pb.start();

                if (isBackground) {
                    Job job = new Job();
                    job.jobId = getNextJobId(backgroundJobs);
                    job.pid = process.pid();
                    job.process = process;
                    job.commandLine = String.join(" ", commandArgs);
                    backgroundJobs.add(job);
                    System.out.println("[" + job.jobId + "] " + job.pid);
                } else {
                    process.waitFor();
                }
            } else {
                System.out.println(command + ": command not found");
            }
        }
    }

    private static void handlePipeline(List<String> parsed, int pipeIndex, Path currentDirectory) throws Exception {
        List<String> leftArgs = new ArrayList<>();
        List<String> rightArgs = new ArrayList<>();

        for (int i = 0; i < pipeIndex; i++) leftArgs.add(parsed.get(i));
        for (int i = pipeIndex + 1; i < parsed.size(); i++) rightArgs.add(parsed.get(i));

        if (leftArgs.isEmpty() || rightArgs.isEmpty()) {
            System.out.println("parse error: near `|'");
            return;
        }

        String leftCmd = leftArgs.get(0);
        String rightCmd = rightArgs.get(0);

        boolean leftIsBuiltin = BUILTINS.contains(leftCmd);
        boolean rightIsBuiltin = BUILTINS.contains(rightCmd);

        if (!leftIsBuiltin && findExecutable(leftCmd) == null) {
            System.out.println(leftCmd + ": command not found");
            return;
        }
        if (!rightIsBuiltin && findExecutable(rightCmd) == null) {
            System.out.println(rightCmd + ": command not found");
            return;
        }

        if (leftIsBuiltin && rightIsBuiltin) {
            executeBuiltin(leftArgs, null, new ByteArrayOutputStream(), currentDirectory);
            executeBuiltin(rightArgs, null, System.out, currentDirectory);
            return;
        }

        if (leftIsBuiltin) {
            // Builtin | External
            try (PipedOutputStream pos = new PipedOutputStream();
                 PipedInputStream pis = new PipedInputStream(pos)) {

                Thread leftThread = new Thread(() -> {
                    try {
                        executeBuiltin(leftArgs, null, pos, currentDirectory);
                    } catch (Exception ignored) {}
                    finally {
                        try { pos.close(); } catch (Exception ignored) {}
                    }
                });
                leftThread.start();

                ProcessBuilder rightPb = new ProcessBuilder(rightArgs);
                rightPb.directory(currentDirectory.toFile());
                rightPb.redirectInput(ProcessBuilder.Redirect.PIPE);
                rightPb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                rightPb.redirectError(ProcessBuilder.Redirect.INHERIT);

                Process rightProcess = rightPb.start();

                Thread pipeThread = new Thread(() -> {
                    try {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = pis.read(buffer)) != -1) {
                            rightProcess.getOutputStream().write(buffer, 0, len);
                            rightProcess.getOutputStream().flush();
                        }
                    } catch (Exception ignored) {}
                    finally {
                        try { rightProcess.getOutputStream().close(); } catch (Exception ignored) {}
                    }
                });
                pipeThread.start();

                rightProcess.waitFor();
                leftThread.join();
                pipeThread.join();
            }
        } else if (rightIsBuiltin) {
            // External | Builtin
            ProcessBuilder leftPb = new ProcessBuilder(leftArgs);
            leftPb.directory(currentDirectory.toFile());
            leftPb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            leftPb.redirectError(ProcessBuilder.Redirect.INHERIT);

            Process leftProcess = leftPb.start();

            Thread rightThread = new Thread(() -> {
                try {
                    executeBuiltin(rightArgs, leftProcess.getInputStream(), System.out, currentDirectory);
                } catch (Exception ignored) {}
            });
            rightThread.start();

            leftProcess.waitFor();
            rightThread.join();
        } else {
            // External | External
            ProcessBuilder leftPb = new ProcessBuilder(leftArgs);
            ProcessBuilder rightPb = new ProcessBuilder(rightArgs);

            leftPb.directory(currentDirectory.toFile());
            rightPb.directory(currentDirectory.toFile());

            leftPb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            rightPb.redirectInput(ProcessBuilder.Redirect.PIPE);
            rightPb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            rightPb.redirectError(ProcessBuilder.Redirect.INHERIT);

            Process leftProcess = leftPb.start();
            Process rightProcess = rightPb.start();

            Thread pipeThread = new Thread(() -> {
                try {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = leftProcess.getInputStream().read(buffer)) != -1) {
                        rightProcess.getOutputStream().write(buffer, 0, len);
                        rightProcess.getOutputStream().flush();
                    }
                } catch (Exception ignored) {}
                finally {
                    try { rightProcess.getOutputStream().close(); } catch (Exception ignored) {}
                }
            });
            pipeThread.start();

            rightProcess.waitFor();
            if (leftProcess.isAlive()) leftProcess.destroy();
            pipeThread.join();
        }
    }

    private static void executeBuiltin(List<String> args, InputStream stdin, OutputStream stdout, Path currentDirectory) throws Exception {
        if (args.isEmpty()) return;
        String cmd = args.get(0);
        PrintStream out = stdout != null ? new PrintStream(stdout) : System.out;

        if ("echo".equals(cmd)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < args.size(); i++) {
                if (i > 1) sb.append(" ");
                sb.append(args.get(i));
            }
            out.println(sb);
        } else if ("pwd".equals(cmd)) {
            out.println(currentDirectory.toAbsolutePath());
        } else if ("type".equals(cmd)) {
            if (args.size() > 1) {
                String target = args.get(1);
                if (BUILTINS.contains(target)) {
                    out.println(target + " is a shell builtin");
                } else {
                    File exec = findExecutable(target);
                    if (exec != null) {
                        out.println(target + " is " + exec.getAbsolutePath());
                    } else {
                        out.println(target + ": not found");
                    }
                }
            }
            if (stdin != null) {
                try {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = stdin.read(buffer)) != -1) {}
                } catch (Exception ignored) {}
            }
        }

        out.flush();
    }

    private static File findExecutable(String command) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        String[] dirs = path.split(File.pathSeparator);
        for (String dir : dirs) {
            File file = new File(dir, command);
            if (file.exists() && file.canExecute()) {
                return file;
            }
        }
        return null;
    }

    private static int getNextJobId(List<Job> jobs) {
        if (jobs.isEmpty()) return 1;
        int max = 0;
        for (Job job : jobs) if (job.jobId > max) max = job.jobId;
        return max + 1;
    }

    private static void reapBackgroundJobs(List<Job> backgroundJobs) {
        List<Job> stillRunning = new ArrayList<>();
        int size = backgroundJobs.size();
        for (int i = 0; i < size; i++) {
            Job job = backgroundJobs.get(i);
            boolean isAlive = true;
            try {
                job.process.exitValue();
                isAlive = false;
            } catch (IllegalThreadStateException e) {}
            
            if (!isAlive) {
                String marker = (i == size - 1) ? "+" : (i == size - 2) ? "-" : " ";
                System.out.println("[" + job.jobId + "]" + marker + "  Done                    " + job.commandLine);
            } else {
                stillRunning.add(job);
            }
        }
        backgroundJobs.clear();
        backgroundJobs.addAll(stillRunning);
    }

    private static List<String> parseCommand(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inSingleQuotes) {
                if (c == '\'') inSingleQuotes = false;
                else current.append(c);
            } else if (inDoubleQuotes) {
                if (c == '\\' && i + 1 < input.length()) {
                    char next = input.charAt(i + 1);
                    if (next == '"' || next == '\\') current.append(next);
                    else current.append(c).append(next);
                    i++;
                } else if (c == '"') {
                    inDoubleQuotes = false;
                } else {
                    current.append(c);
                }
            } else {
                if (c == '\\' && i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                } else if (c == '\'') {
                    inSingleQuotes = true;
                } else if (c == '"') {
                    inDoubleQuotes = true;
                } else if (c == '|' || c == '>') {
                    if (current.length() > 0) {
                        args.add(current.toString());
                        current.setLength(0);
                    }
                    if (c == '|') {
                        args.add("|");
                    } else {
                        boolean append = (i + 1 < input.length() && input.charAt(i + 1) == '>');
                        if (append) i++;
                        args.add(append ? ">>" : ">");
                    }
                } else if (Character.isWhitespace(c)) {
                    if (current.length() > 0) {
                        args.add(current.toString());
                        current.setLength(0);
                    }
                } else {
                    current.append(c);
                }
            }
        }

        if (current.length() > 0) args.add(current.toString());
        return args;
    }
}