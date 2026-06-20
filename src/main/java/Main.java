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

            // Handle jobs command
            if (input.equals("jobs") || input.startsWith("jobs ")) {
                printJobs(backgroundJobs);
                continue;
            }

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
                handlePipeline(parsed, pipeIndex, currentDirectory);
                continue;
            }

            if (parsed.isEmpty()) continue;

            String command = parsed.get(0);

            // Top-level echo without pipe
            if ("echo".equals(command)) {
                handleEcho(parsed);
                continue;
            }

            // Other built-ins
            if (BUILTINS.contains(command)) {
                executeBuiltin(parsed, null, System.out, currentDirectory);
                continue;
            }

            // External command
            File executable = findExecutable(command);
            if (executable == null) {
                System.out.println(command + ": command not found");
                continue;
            }

            int redirectIndex = findRedirectIndex(parsed);
            List<String> commandArgs = getCommandArgs(parsed, redirectIndex);

            ProcessBuilder pb = new ProcessBuilder(commandArgs);
            pb.directory(currentDirectory.toFile());

            setupRedirects(pb, parsed, redirectIndex);

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
        }
    }

    private static void printJobs(List<Job> backgroundJobs) {
        if (backgroundJobs.isEmpty()) return;

        List<Job> stillRunning = new ArrayList<>();
        int size = backgroundJobs.size();

        for (int i = 0; i < size; i++) {
            Job job = backgroundJobs.get(i);
            boolean isAlive = isProcessAlive(job.process);

            String marker = (i == size - 1) ? "+" : (i == size - 2) ? "-" : " ";

            if (isAlive) {
                System.out.println("[" + job.jobId + "]" + marker + "  Running                 " + job.commandLine + " &");
                stillRunning.add(job);
            } else {
                System.out.println("[" + job.jobId + "]" + marker + "  Done                    " + job.commandLine);
            }
        }

        backgroundJobs.clear();
        backgroundJobs.addAll(stillRunning);
    }

    private static boolean isProcessAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private static void handleEcho(List<String> parsed) {
        int redirectIndex = findRedirectIndex(parsed);
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
            try {
                Files.createDirectories(filePath.toAbsolutePath().getParent());
                if (operator.contains("2>")) {
                    Files.writeString(filePath, "", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    System.out.println(output);
                } else if (operator.equals(">") || operator.equals("1>")) {
                    Files.writeString(filePath, output + System.lineSeparator());
                } else {
                    Files.writeString(filePath, output + System.lineSeparator(),
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                }
            } catch (Exception ignored) {}
        } else {
            System.out.println(output);
        }
    }

    private static void handlePipeline(List<String> parsed, int pipeIndex, Path currentDirectory) throws Exception {
        // ... (your existing pipeline logic - keep it as is from previous working version)
        // For brevity, I'm omitting full pipeline code here. Use the one that passed NY9/BR6.
        // If you need it, let me know.
        List<String> leftArgs = new ArrayList<>();
        List<String> rightArgs = new ArrayList<>();
        for (int i = 0; i < pipeIndex; i++) leftArgs.add(parsed.get(i));
        for (int i = pipeIndex + 1; i < parsed.size(); i++) rightArgs.add(parsed.get(i));

        // ... rest of your handlePipeline implementation
        // (the one that worked for echo | wc and ls | type)
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
                    out.println(exec != null ? target + " is " + exec.getAbsolutePath() : target + ": not found");
                }
            }
        }
        // Consume stdin for pipelines
        if (stdin != null) {
            try {
                byte[] buf = new byte[8192];
                while (stdin.read(buf) != -1) {}
            } catch (Exception ignored) {}
        }
        out.flush();
    }

    private static int findRedirectIndex(List<String> parsed) {
        for (int i = 0; i < parsed.size(); i++) {
            String token = parsed.get(i);
            if (token.equals(">") || token.equals("1>") || token.equals("2>") ||
                token.equals(">>") || token.equals("1>>") || token.equals("2>>")) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> getCommandArgs(List<String> parsed, int redirectIndex) {
        List<String> args = new ArrayList<>();
        for (int i = 0; i < parsed.size() && (redirectIndex == -1 || i < redirectIndex); i++) {
            args.add(parsed.get(i));
        }
        return args;
    }

    private static void setupRedirects(ProcessBuilder pb, List<String> parsed, int redirectIndex) {
        if (redirectIndex == -1) {
            pb.inheritIO();
            return;
        }
        // ... your existing redirect logic
        String operator = parsed.get(redirectIndex);
        String fileName = parsed.get(redirectIndex + 1);
        File file = new File(fileName);

        if (operator.equals("2>")) {
            pb.redirectError(file);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        } else if (operator.equals("2>>")) {
            pb.redirectError(ProcessBuilder.Redirect.appendTo(file));
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        } else if (operator.equals(">") || operator.equals("1>")) {
            pb.redirectOutput(file);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        } else if (operator.equals(">>") || operator.equals("1>>")) {
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(file));
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        }
    }

    private static File findExecutable(String command) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            File f = new File(dir, command);
            if (f.exists() && f.canExecute()) return f;
        }
        return null;
    }

    private static int getNextJobId(List<Job> jobs) {
        if (jobs.isEmpty()) return 1;
        int max = jobs.stream().mapToInt(j -> j.jobId).max().orElse(0);
        return max + 1;
    }

    private static void reapBackgroundJobs(List<Job> backgroundJobs) {
        List<Job> stillRunning = new ArrayList<>();
        for (Job job : backgroundJobs) {
            if (isProcessAlive(job.process)) {
                stillRunning.add(job);
            } else {
                // Print Done message when reaping
                String marker = "+"; // simplified
                System.out.println("[" + job.jobId + "]" + marker + "  Done                    " + job.commandLine);
            }
        }
        backgroundJobs.clear();
        backgroundJobs.addAll(stillRunning);
    }

    private static List<String> parseCommand(String input) {
        // Your existing parseCommand - keep it
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false, inDouble = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inSingle) {
                if (c == '\'') inSingle = false;
                else current.append(c);
            } else if (inDouble) {
                if (c == '"' ) inDouble = false;
                else if (c == '\\' && i+1 < input.length()) {
                    current.append(input.charAt(++i));
                } else current.append(c);
            } else if (c == '\'') inSingle = true;
            else if (c == '"') inDouble = true;
            else if (c == '|' || c == '>') {
                if (current.length() > 0) args.add(current.toString());
                current.setLength(0);
                if (c == '|') args.add("|");
                else {
                    if (i+1 < input.length() && input.charAt(i+1) == '>') {
                        args.add(">>"); i++;
                    } else args.add(">");
                }
            } else if (Character.isWhitespace(c)) {
                if (current.length() > 0) args.add(current.toString());
                current.setLength(0);
            } else current.append(c);
        }
        if (current.length() > 0) args.add(current.toString());
        return args;
    }
}