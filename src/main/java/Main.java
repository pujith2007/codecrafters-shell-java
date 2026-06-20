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

            else if (input.equals("jobs") || input.startsWith("jobs ")) {

                List<Job> stillRunning = new ArrayList<>();

                int size = backgroundJobs.size();
                for (int i = 0; i < size; i++) {
                    Job job = backgroundJobs.get(i);

                    boolean isAlive;
                    try {
                        job.process.exitValue();
                        isAlive = false;
                    } catch (IllegalThreadStateException e) {
                        isAlive = true;
                    }

                    String marker;
                    if (i == size - 1) {
                        marker = "+";
                    } else if (i == size - 2) {
                        marker = "-";
                    } else {
                        marker = " ";
                    }

                    if (isAlive) {
                        String status = "Running";
                        String padding = "                 ";
                        System.out.println("[" + job.jobId + "]" + marker + "  " + status + padding + job.commandLine + " &");
                        stillRunning.add(job);
                    } else {
                        String status = "Done";
                        String padding = "                    ";
                        System.out.println("[" + job.jobId + "]" + marker + "  " + status + padding + job.commandLine);
                    }
                }

                backgroundJobs.clear();
                backgroundJobs.addAll(stillRunning);
                continue;
            }

            else if (input.startsWith("echo ")) {

                List<String> parsed = parseCommand(input);

                int redirectIndex = -1;

                for (int i = 0; i < parsed.size(); i++) {
                    if (parsed.get(i).equals(">")
                        || parsed.get(i).equals("1>")
                        || parsed.get(i).equals("2>")
                        || parsed.get(i).equals("2>>")
                        || parsed.get(i).equals(">>")
                        || parsed.get(i).equals("1>>")) {
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

                    if (operator.equals("2>")) {
                        Files.writeString(filePath, "");
                        System.out.println(output);
                    } else if (operator.equals("2>>")) {
                        Files.writeString(
                                filePath,
                                "",
                                java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.APPEND);
                        System.out.println(output);
                    } else if (operator.equals(">") || operator.equals("1>")) {
                        Files.writeString(filePath,
                                output.toString() + System.lineSeparator());
                    } else if (operator.equals(">>") || operator.equals("1>>")) {
                        Files.writeString(filePath,
                                output.toString() + System.lineSeparator(),
                                java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.APPEND);
                    }

                } else {
                    System.out.println(output);
                }

            }

            else if (input.equals("pwd")) {
                System.out.println(currentDirectory.toAbsolutePath());
            }

            else if (input.startsWith("cd ")) {
                String dirName = input.substring(3).trim();
                Path newPath;
                if (dirName.equals("~")) {
                    newPath = Path.of(System.getenv("HOME"));
                } else if (dirName.startsWith("/")) {
                    newPath = Path.of(dirName);
                } else {
                    newPath = currentDirectory.resolve(dirName);
                }
                newPath = newPath.normalize();
                if (newPath.toFile().exists() && newPath.toFile().isDirectory()) {
                    currentDirectory = newPath;
                } else {
                    System.out.println("cd: " + dirName + ": No such file or directory");
                }
            }

            else if (input.startsWith("type ")) {
                String command = input.substring(5).trim();
                if (BUILTINS.contains(command)) {
                    System.out.println(command + " is a shell builtin");
                } else {
                    File exec = findExecutable(command);
                    if (exec != null) {
                        System.out.println(command + " is " + exec.getAbsolutePath());
                    } else {
                        System.out.println(command + ": not found");
                    }
                }
            }

            else {
                List<String> parsed = parseCommand(input);

                int pipeIndex = -1;
                for (int i = 0; i < parsed.size(); i++) {
                    if (parsed.get(i).equals("|")) {
                        pipeIndex = i;
                        break;
                    }
                }

                boolean isBackground = false;
                if (pipeIndex == -1 && !parsed.isEmpty() && parsed.get(parsed.size() - 1).equals("&")) {
                    isBackground = true;
                    parsed.remove(parsed.size() - 1);
                }

                if (pipeIndex != -1) {
                    // Pipeline support including built-ins
                    handlePipeline(parsed, pipeIndex, currentDirectory);
                    continue;
                }

                // Non-pipe command (existing logic)
                int redirectIndex = -1;
                for (int i = 0; i < parsed.size(); i++) {
                    if (parsed.get(i).equals(">")
                            || parsed.get(i).equals("2>>")
                            || parsed.get(i).equals("1>")
                            || parsed.get(i).equals("2>")
                            || parsed.get(i).equals(">>")
                            || parsed.get(i).equals("1>>")) {
                        redirectIndex = i;
                        break;
                    }
                }

                List<String> commandArgs = new ArrayList<>();
                for (int i = 0; i < parsed.size(); i++) {
                    if (i == redirectIndex) break;
                    commandArgs.add(parsed.get(i));
                }

                if (commandArgs.isEmpty()) continue;

                String command = commandArgs.get(0);
                String[] parts = commandArgs.toArray(new String[0]);

                if (BUILTINS.contains(command)) {
                    // Built-in not in pipeline
                    try {
                        executeBuiltin(commandArgs, null, System.out, currentDirectory);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    continue;
                }

                File executable = findExecutable(command);

                if (executable != null) {
                    ProcessBuilder pb = new ProcessBuilder(parts);
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
    }

    private static void handlePipeline(List<String> parsed, int pipeIndex, Path currentDirectory) throws Exception {
        List<String> leftArgs = new ArrayList<>();
        List<String> rightArgs = new ArrayList<>();

        for (int i = 0; i < pipeIndex; i++) {
            leftArgs.add(parsed.get(i));
        }
        for (int i = pipeIndex + 1; i < parsed.size(); i++) {
            rightArgs.add(parsed.get(i));
        }

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
            // Rare case - run left (output discarded), then right
            executeBuiltin(leftArgs, null, new ByteArrayOutputStream(), currentDirectory);
            executeBuiltin(rightArgs, null, System.out, currentDirectory);
            return;
        }

        if (leftIsBuiltin) {
            // Builtin | external
            try (PipedOutputStream pos = new PipedOutputStream();
                 PipedInputStream pis = new PipedInputStream(pos)) {

                Thread leftThread = new Thread(() -> {
                    try {
                        executeBuiltin(leftArgs, null, pos, currentDirectory);
                    } catch (Exception ignored) {
                    } finally {
                        try { pos.close(); } catch (Exception ignored) {}
                    }
                });
                leftThread.start();

                ProcessBuilder rightPb = new ProcessBuilder(rightArgs);
                rightPb.directory(currentDirectory.toFile());
                rightPb.redirectInput(pis);
                rightPb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                rightPb.redirectError(ProcessBuilder.Redirect.INHERIT);

                Process rightProcess = rightPb.start();
                rightProcess.waitFor();
                leftThread.join();
            }
        } else if (rightIsBuiltin) {
            // external | builtin
            ProcessBuilder leftPb = new ProcessBuilder(leftArgs);
            leftPb.directory(currentDirectory.toFile());
            leftPb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            leftPb.redirectError(ProcessBuilder.Redirect.INHERIT);

            Process leftProcess = leftPb.start();

            Thread rightThread = new Thread(() -> {
                try {
                    executeBuiltin(rightArgs, leftProcess.getInputStream(), System.out, currentDirectory);
                } catch (Exception ignored) {
                }
            });
            rightThread.start();

            leftProcess.waitFor();
            rightThread.join();
        } else {
            // external | external (original logic)
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
                } catch (Exception ignored) {
                } finally {
                    try {
                        rightProcess.getOutputStream().close();
                    } catch (Exception ignored) {}
                }
            });
            pipeThread.start();

            rightProcess.waitFor();

            if (leftProcess.isAlive()) {
                leftProcess.destroy();
            }
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
        } else if ("cd".equals(cmd) || "jobs".equals(cmd) || "exit".equals(cmd)) {
            // No-op or not supported in pipeline
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
        if (jobs.isEmpty()) {
            return 1;
        }
        int max = 0;
        for (Job job : jobs) {
            if (job.jobId > max) {
                max = job.jobId;
            }
        }
        return max + 1;
    }

    private static void reapBackgroundJobs(List<Job> backgroundJobs) {
        List<Job> stillRunning = new ArrayList<>();

        int size = backgroundJobs.size();
        for (int i = 0; i < size; i++) {
            Job job = backgroundJobs.get(i);

            boolean isAlive;
            try {
                job.process.exitValue();
                isAlive = false;
            } catch (IllegalThreadStateException e) {
                isAlive = true;
            }

            if (!isAlive) {
                String marker;
                if (i == size - 1) {
                    marker = "+";
                } else if (i == size - 2) {
                    marker = "-";
                } else {
                    marker = " ";
                }
                String status = "Done";
                String padding = "                    ";
                System.out.println("[" + job.jobId + "]" + marker + "  " + status + padding + job.commandLine);
            } else {
                stillRunning.add(job);
            }
        }

        backgroundJobs.clear();
        backgroundJobs.addAll(stillRunning);
    }

    private static List<String> parseCommand(String input) {
        // (unchanged - includes the 2>> fix from previous stage)
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {

            char c = input.charAt(i);

            if (inSingleQuotes) {
                if (c == '\'') inSingleQuotes = false;
                else current.append(c);
            }

            else if (inDoubleQuotes) {

                if (c == '\\') {

                    if (i + 1 < input.length()) {
                        char next = input.charAt(i + 1);

                        if (next == '"' || next == '\\') {
                            current.append(next);
                            i++;
                        } else {
                            current.append('\\');
                            current.append(next);
                            i++;
                        }
                    } else {
                        current.append('\\');
                    }

                } else if (c == '"') {
                    inDoubleQuotes = false;
                } else {
                    current.append(c);
                }
            }

            else {

                if (c == '\\') {

                    if (i + 1 < input.length()) {
                        current.append(input.charAt(i + 1));
                        i++;
                    }

                } else if (c == '\'') {
                    inSingleQuotes = true;

                } else if (c == '"') {
                    inDoubleQuotes = true;

                } else if (c == '|' || c == '>') {

                    if (current.length() > 0) {
                        String prev = current.toString();
                        if (c == '>' && prev.length() > 0 && Character.isDigit(prev.charAt(prev.length() - 1))) {
                            boolean append = (i + 1 < input.length() && input.charAt(i + 1) == '>');
                            if (append) i++;
                            String op = prev + (append ? ">>" : ">");
                            args.add(op);
                            current.setLength(0);
                            continue;
                        } else {
                            args.add(prev);
                            current.setLength(0);
                        }
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

        if (current.length() > 0) {
            args.add(current.toString());
        }

        return args;
    }
}