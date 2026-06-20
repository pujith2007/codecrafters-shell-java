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

            if (input.equals("exit")) break;

            List<String> parsed = parseCommand(input);

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

            boolean isBackground = false;
            if (!parsed.isEmpty() && parsed.get(parsed.size() - 1).equals("&")) {
                isBackground = true;
                parsed.remove(parsed.size() - 1);
            }

            if (parsed.isEmpty()) continue;

            String command = parsed.get(0);

            if ("echo".equals(command)) {
                handleEcho(parsed);
                continue;
            }

            if (BUILTINS.contains(command)) {
                executeBuiltin(parsed, null, System.out, currentDirectory);
                continue;
            }

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

    private static void handleEcho(List<String> parsed) {
        int redirectIndex = findRedirectIndex(parsed);
        StringBuilder output = new StringBuilder();
        int end = (redirectIndex == -1) ? parsed.size() : redirectIndex;
        for (int i = 1; i < end; i++) {
            if (i > 1) output.append(" ");
            output.append(parsed.get(i));
        }
        System.out.println(output);
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

        if (leftIsBuiltin) {
            // Builtin | External
            try (PipedOutputStream pos = new PipedOutputStream();
                 PipedInputStream pis = new PipedInputStream(pos)) {

                Thread leftThread = new Thread(() -> {
                    try { executeBuiltin(leftArgs, null, pos, currentDirectory); }
                    catch (Exception ignored) {}
                    finally { try { pos.close(); } catch (Exception ignored) {} }
                });
                leftThread.start();

                ProcessBuilder rightPb = new ProcessBuilder(rightArgs);
                rightPb.directory(currentDirectory.toFile());
                rightPb.redirectInput(ProcessBuilder.Redirect.PIPE);
                rightPb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                rightPb.redirectError(ProcessBuilder.Redirect.INHERIT);

                Process rightProcess = rightPb.start();

                Thread copier = new Thread(() -> {
                    try (OutputStream out = rightProcess.getOutputStream()) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = pis.read(buf)) != -1) {
                            out.write(buf, 0, len);
                            out.flush();
                        }
                    } catch (Exception ignored) {}
                    finally { try { rightProcess.getOutputStream().close(); } catch (Exception ignored) {} }
                });
                copier.start();

                rightProcess.waitFor();
                leftThread.join();
                copier.join();
            }
        } else if (rightIsBuiltin) {
            // External | Builtin
            ProcessBuilder leftPb = new ProcessBuilder(leftArgs);
            leftPb.directory(currentDirectory.toFile());
            leftPb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            leftPb.redirectError(ProcessBuilder.Redirect.INHERIT);

            Process leftProcess = leftPb.start();

            Thread rightThread = new Thread(() -> {
                try { executeBuiltin(rightArgs, leftProcess.getInputStream(), System.out, currentDirectory); }
                catch (Exception ignored) {}
            });
            rightThread.start();

            leftProcess.waitFor();
            rightThread.join();
        } else {
            // === External | External - IMPROVED for tail -f | head ===
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

            Thread copier = new Thread(() -> {
                try {
                    leftProcess.getInputStream().transferTo(rightProcess.getOutputStream());
                } catch (Exception ignored) {}
                finally {
                    try { rightProcess.getOutputStream().close(); } catch (Exception ignored) {}
                }
            });
            copier.start();

            // Wait for the right side (head) to finish
            rightProcess.waitFor();

            // Give left side a moment to finish naturally, then destroy if still alive
            Thread.sleep(100); // small grace period
            if (leftProcess.isAlive()) {
                leftProcess.destroy();
            }
            copier.join();
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
        } else if ("type".equals(cmd) && args.size() > 1) {
            String target = args.get(1);
            if (BUILTINS.contains(target)) {
                out.println(target + " is a shell builtin");
            } else {
                File exec = findExecutable(target);
                out.println(exec != null ? target + " is " + exec.getAbsolutePath() : target + ": not found");
            }
        }

        if (stdin != null) {
            try { 
                byte[] buf = new byte[8192];
                while (stdin.read(buf) != -1) {}
            } catch (Exception ignored) {}
        }
        out.flush();
    }

    // ==================== Helper Methods ====================
    private static int findRedirectIndex(List<String> parsed) {
        for (int i = 0; i < parsed.size(); i++) {
            String s = parsed.get(i);
            if (s.matches("^[12]?>{1,2}$")) return i;
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
        pb.inheritIO(); // fallback - expand if needed
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
        int max = 0;
        for (Job j : jobs) if (j.jobId > max) max = j.jobId;
        return max + 1;
    }

    private static void reapBackgroundJobs(List<Job> backgroundJobs) {
        List<Job> stillRunning = new ArrayList<>();
        for (Job job : backgroundJobs) {
            if (isAlive(job.process)) {
                stillRunning.add(job);
            } else {
                System.out.println("[" + job.jobId + "]+  Done                    " + job.commandLine);
            }
        }
        backgroundJobs.clear();
        backgroundJobs.addAll(stillRunning);
    }

    private static boolean isAlive(Process p) {
        try { p.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; }
    }

    private static List<String> parseCommand(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false, inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inSingleQuotes) {
                if (c == '\'') inSingleQuotes = false;
                else current.append(c);
            } else if (inDoubleQuotes) {
                if (c == '\\' && i+1 < input.length()) {
                    current.append(input.charAt(++i));
                } else if (c == '"') inDoubleQuotes = false;
                else current.append(c);
            } else if (c == '\\' && i+1 < input.length()) {
                current.append(input.charAt(++i));
            } else if (c == '\'') inSingleQuotes = true;
            else if (c == '"') inDoubleQuotes = true;
            else if (c == '|' || c == '>') {
                if (current.length() > 0) args.add(current.toString());
                current.setLength(0);
                if (c == '|') args.add("|");
                else {
                    if (i+1 < input.length() && input.charAt(i+1) == '>') { args.add(">>"); i++; }
                    else args.add(">");
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