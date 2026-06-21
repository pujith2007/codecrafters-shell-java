import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {
    private static class BackgroundJob {
        int jobId;        
        long pid;
        String rawCommand;
        Process process;

        BackgroundJob(int jobId, long pid, String rawCommand, Process process) {
            this.jobId = jobId;
            this.pid = pid;
            this.rawCommand = rawCommand;
            this.process = process;
        }
    }

    private static final List<BackgroundJob> activeJobs = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        while (true) {
            System.out.print("$ ");
            String input = scanner.nextLine().trim();
            
            if (input.isEmpty()) {
                reapJobsEngine(false);
                continue;
            }

            List<String> rawArgsList = parseArguments(input);
            if (rawArgsList.isEmpty()) continue;

            boolean isBackgroundJob = false;
            if (rawArgsList.get(rawArgsList.size() - 1).equals("&")) {
                isBackgroundJob = true;
                rawArgsList.remove(rawArgsList.size() - 1);
            }

            if (rawArgsList.isEmpty()) continue;

            String rawCommandString = String.join(" ", rawArgsList);

            // --- PIPELINE DETECTION ENGINE ---
            int pipeIndex = -1;
            for (int i = 0; i < rawArgsList.size(); i++) {
                if (rawArgsList.get(i).equals("|")) {
                    pipeIndex = i;
                    break;
                }
            }

            if (pipeIndex != -1) {
                List<String> leftRaw = new ArrayList<>(rawArgsList.subList(0, pipeIndex));
                List<String> rightRaw = new ArrayList<>(rawArgsList.subList(pipeIndex + 1, rawArgsList.size()));
                
                handlePipelineExecution(leftRaw, rightRaw, isBackgroundJob, rawCommandString);
                
                if (!leftRaw.get(0).equals("jobs") && !rightRaw.get(0).equals("jobs")) {
                    reapJobsEngine(false);
                }
                continue;
            }

            // --- SINGLE COMMAND ROUTING ENGINE ---
            String redirectFile = null;
            String redirectErrFile = null;
            boolean appendMode = false;
            boolean appendErrMode = false;

            List<String> argsList = new ArrayList<>();
            for (int i = 0; i < rawArgsList.size(); i++) {
                String arg = rawArgsList.get(i);
                if (arg.equals(">") || arg.equals("1>")) {
                    redirectFile = rawArgsList.get(++i);
                    appendMode = false;
                } else if (arg.equals(">>") || arg.equals("1>>")) {
                    redirectFile = rawArgsList.get(++i);
                    appendMode = true;
                } else if (arg.equals("2>")) {
                    redirectErrFile = rawArgsList.get(++i);
                    appendErrMode = false;
                } else if (arg.equals("2>>")) {
                    redirectErrFile = rawArgsList.get(++i);
                    appendErrMode = true;
                } else {
                    argsList.add(arg);
                }
            }

            if (argsList.isEmpty()) continue;
            String command = argsList.get(0);

            PrintStream fileOut = null;
            PrintStream fileErr = null;

            try {
                if (redirectFile != null) {
                    File file = Paths.get(System.getProperty("user.dir")).resolve(redirectFile).toFile();
                    fileOut = new PrintStream(new FileOutputStream(file, appendMode));
                    System.setOut(fileOut);
                }
                if (redirectErrFile != null) {
                    File file = Paths.get(System.getProperty("user.dir")).resolve(redirectErrFile).toFile();
                    fileErr = new PrintStream(new FileOutputStream(file, appendErrMode));
                    System.setErr(fileErr);
                }

                executeSingleCommand(command, argsList, isBackgroundJob, rawCommandString, redirectFile, redirectErrFile, appendMode, appendErrMode);

            } catch (Exception e) {
                System.err.println("Execution error: " + e.getMessage());
            } finally {
                if (fileOut != null) { fileOut.flush(); fileOut.close(); }
                if (fileErr != null) { fileErr.flush(); fileErr.close(); }
                System.setOut(originalOut);
                System.setErr(originalErr);
            }

            if (!command.equals("jobs")) {
                reapJobsEngine(false);
            }
        }
    }

    private static void executeSingleCommand(String command, List<String> argsList, boolean isBackgroundJob, String rawCommandString, 
                                             String redirectFile, String redirectErrFile, boolean appendMode, boolean appendErrMode) throws Exception {
        if (command.equals("exit")) {
            System.exit(0);
        } 
        else if (command.equals("echo")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < argsList.size(); i++) {
                sb.append(argsList.get(i)).append(i < argsList.size() - 1 ? " " : "");
            }
            System.out.println(sb.toString());
        } 
        else if (command.equals("pwd")) {
            System.out.println(System.getProperty("user.dir"));
        } 
        else if (command.equals("cd")) {
            String targetPathStr = argsList.size() > 1 ? argsList.get(1) : "~";
            if (targetPathStr.equals("~")) {
                targetPathStr = System.getenv("HOME");
                if (targetPathStr == null) targetPathStr = System.getProperty("user.home");
            }
            Path targetPath = Paths.get(System.getProperty("user.dir")).resolve(targetPathStr).normalize();
            if (targetPath.toFile().exists() && targetPath.toFile().isDirectory()) {
                System.setProperty("user.dir", targetPath.toAbsolutePath().toString());
            } else {
                System.out.println("cd: " + targetPathStr + ": No such file or directory");
            }
        } 
        else if (command.equals("jobs")) {
            reapJobsEngine(true);
        }
        else if (command.equals("type")) {
            if (argsList.size() > 1) {
                String commandToCheck = argsList.get(1);
                if (isBuiltin(commandToCheck)) {
                    System.out.println(commandToCheck + " is a shell builtin");
                } else {
                    String fullPath = getPathFromEnv(commandToCheck);
                    if (fullPath != null) System.out.println(commandToCheck + " is " + fullPath);
                    else System.out.println(commandToCheck + ": not found");
                }
            }
        } 
        else {
            String fullPath = getPathFromEnv(command);
            File directFile = new File(command);
            if (fullPath != null || directFile.isAbsolute() || command.startsWith("./") || command.startsWith("../")) {
                ProcessBuilder processBuilder = new ProcessBuilder(argsList);
                processBuilder.directory(new File(System.getProperty("user.dir")));
                
                // FIX: Dynamically handle standard output redirection settings for child processes
                if (redirectFile != null) {
                    File file = Paths.get(System.getProperty("user.dir")).resolve(redirectFile).toFile();
                    processBuilder.redirectOutput(appendMode ? ProcessBuilder.Redirect.appendTo(file) : ProcessBuilder.Redirect.to(file));
                } else {
                    processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }

                // FIX: Dynamically handle standard error redirection settings for child processes
                if (redirectErrFile != null) {
                    File file = Paths.get(System.getProperty("user.dir")).resolve(redirectErrFile).toFile();
                    processBuilder.redirectError(appendErrMode ? ProcessBuilder.Redirect.appendTo(file) : ProcessBuilder.Redirect.to(file));
                } else {
                    processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                processBuilder.redirectInput(ProcessBuilder.Redirect.INHERIT);
                Process process = processBuilder.start();
                
                if (!isBackgroundJob) {
                    process.waitFor();
                } else {
                    int assignedJobId = calculateNextJobId();
                    activeJobs.add(new BackgroundJob(assignedJobId, process.pid(), rawCommandString, process));
                    System.out.println("[" + assignedJobId + "] " + process.pid());
                }
            } else {
                System.err.println(command + ": command not found");
            }
        }
    }

    private static boolean isBuiltin(String command) {
        return command.equals("echo") || command.equals("exit") || 
               command.equals("type") || command.equals("pwd")  || 
               command.equals("cd")   || command.equals("jobs");
    }

    private static List<String> transformArgsForPipeline(List<String> rawCommandTokens) {
        List<String> processedArgs = new ArrayList<>();
        for (int i = 0; i < rawCommandTokens.size(); i++) {
            String token = rawCommandTokens.get(i);
            if (token.equals(">") || token.equals("1>") || token.equals(">>") || 
                token.equals("1>>") || token.equals("2>") || token.equals("2>>")) {
                i++;
            } else {
                processedArgs.add(token);
            }
        }

        if (processedArgs.isEmpty()) return processedArgs;

        String cmd = processedArgs.get(0);
        if (isBuiltin(cmd)) {
            String reconstructedCmd = String.join(" ", processedArgs);
            if (cmd.equals("type") && processedArgs.size() == 1) {
                return List.of("sh", "-c", "read input; type \"$input\"");
            }
            if (cmd.equals("type") && processedArgs.size() > 1) {
                String checkTarget = processedArgs.get(1);
                if (isBuiltin(checkTarget)) {
                    return List.of("sh", "-c", "echo \"" + checkTarget + " is a shell builtin\"");
                } else {
                    String fullPath = getPathFromEnv(checkTarget);
                    if (fullPath != null) {
                        return List.of("sh", "-c", "echo \"" + checkTarget + " is " + fullPath + "\"");
                    } else {
                        return List.of("sh", "-c", "echo \"" + checkTarget + ": not found\"");
                    }
                }
            }
            return List.of("sh", "-c", reconstructedCmd);
        }
        return processedArgs;
    }

    private static void handlePipelineExecution(List<String> leftRaw, List<String> rightRaw, boolean isBackground, String rawCmdStr) {
        List<String> leftArgs = transformArgsForPipeline(leftRaw);
        String leftRedirectErrFile = null;
        boolean leftAppendErr = false;
        
        for (int i = 0; i < leftRaw.size(); i++) {
            String arg = leftRaw.get(i);
            if (arg.equals("2>")) { leftRedirectErrFile = leftRaw.get(++i); leftAppendErr = false; }
            else if (arg.equals("2>>")) { leftRedirectErrFile = leftRaw.get(++i); leftAppendErr = true; }
        }

        List<String> rightArgs = transformArgsForPipeline(rightRaw);
        String rightRedirectFile = null, rightRedirectErrFile = null;
        boolean rightAppend = false, rightAppendErr = false;
        
        for (int i = 0; i < rightRaw.size(); i++) {
            String arg = rightRaw.get(i);
            if (arg.equals(">") || arg.equals("1>")) { rightRedirectFile = rightRaw.get(++i); rightAppend = false; }
            else if (arg.equals(">>") || arg.equals("1>>")) { rightRedirectFile = rightRaw.get(++i); rightAppend = true; }
            else if (arg.equals("2>")) { rightRedirectErrFile = rightRaw.get(++i); rightAppendErr = false; }
            else if (arg.equals("2>>")) { rightRedirectErrFile = rightRaw.get(++i); rightAppendErr = true; }
        }

        try {
            ProcessBuilder pb1 = new ProcessBuilder(leftArgs);
            pb1.directory(new File(System.getProperty("user.dir")));
            pb1.redirectInput(ProcessBuilder.Redirect.INHERIT);
            
            if (leftRedirectErrFile != null) {
                File file = Paths.get(System.getProperty("user.dir")).resolve(leftRedirectErrFile).toFile();
                pb1.redirectError(leftAppendErr ? ProcessBuilder.Redirect.appendTo(file) : ProcessBuilder.Redirect.to(file));
            } else {
                pb1.redirectError(ProcessBuilder.Redirect.INHERIT);
            }

            ProcessBuilder pb2 = new ProcessBuilder(rightArgs);
            pb2.directory(new File(System.getProperty("user.dir")));
            
            if (rightRedirectFile != null) {
                File file = Paths.get(System.getProperty("user.dir")).resolve(rightRedirectFile).toFile();
                pb2.redirectOutput(rightAppend ? ProcessBuilder.Redirect.appendTo(file) : ProcessBuilder.Redirect.to(file));
            } else {
                pb2.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }

            if (rightRedirectErrFile != null) {
                File file = Paths.get(System.getProperty("user.dir")).resolve(rightRedirectErrFile).toFile();
                pb2.redirectError(rightAppendErr ? ProcessBuilder.Redirect.appendTo(file) : ProcessBuilder.Redirect.to(file));
            } else {
                pb2.redirectError(ProcessBuilder.Redirect.INHERIT);
            }

            List<ProcessBuilder> builders = List.of(pb1, pb2);
            List<Process> processes = ProcessBuilder.startPipeline(builders);
            Process lastProcess = processes.get(processes.size() - 1);
            
            if (!isBackground) {
                for (Process p : processes) {
                    p.waitFor();
                }
            } else {
                int assignedJobId = calculateNextJobId();
                activeJobs.add(new BackgroundJob(assignedJobId, lastProcess.pid(), new String(rawCmdStr), lastProcess));
                System.out.println("[" + assignedJobId + "] " + lastProcess.pid());
            }
        } catch (Exception e) {
            System.err.println("Pipeline execution error occurred");
        }
    }

    private static int calculateNextJobId() {
        int assignedJobId = 1;
        if (!activeJobs.isEmpty()) {
            int maxId = 0;
            for (BackgroundJob activeJob : activeJobs) {
                if (activeJob.jobId > maxId) maxId = activeJob.jobId;
            }
            assignedJobId = maxId + 1;
        }
        return assignedJobId;
    }

    private static void reapJobsEngine(boolean printRunning) {
        int totalJobs = activeJobs.size();
        List<BackgroundJob> jobsToRemove = new ArrayList<>();

        for (int i = 0; i < totalJobs; i++) {
            BackgroundJob job = activeJobs.get(i);
            char marker = ' ';
            if (i == totalJobs - 1) marker = '+';
            else if (i == totalJobs - 2) marker = '-';

            if (job.process.isAlive()) {
                if (printRunning) {
                    System.out.printf("[%d]%c  Running                  %s &\n", job.jobId, marker, job.rawCommand);
                }
            } else {
                System.out.printf("[%d]%c  Done                     %s\n", job.jobId, marker, job.rawCommand);
                jobsToRemove.add(job);
            }
        }
        activeJobs.removeAll(jobsToRemove);
    }

    private static List<String> parseArguments(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        boolean inSingleQuotes = false, inDoubleQuotes = false;
        
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inSingleQuotes) {
                if (c == '\'') inSingleQuotes = false;
                else currentToken.append(c);
            } else if (inDoubleQuotes) {
                if (c == '"') inDoubleQuotes = false;
                else if (c == '\\' && i + 1 < input.length() && "\\\"$`".indexOf(input.charAt(i + 1)) != -1) {
                    currentToken.append(input.charAt(++i));
                } else {
                    currentToken.append(c);
                }
            } else {
                if (c == '\'') inSingleQuotes = true;
                else if (c == '"') inDoubleQuotes = true;
                else if (c == '\\' && i + 1 < input.length()) currentToken.append(input.charAt(++i));
                else if (Character.isWhitespace(c)) {
                    if (currentToken.length() > 0) {
                        tokens.add(currentToken.toString());
                        currentToken.setLength(0);
                    }
                } else currentToken.append(c);
            }
        }
        if (currentToken.length() > 0) tokens.add(currentToken.toString());
        return tokens;
    }

    private static String getPathFromEnv(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) return null;
        String[] directories = pathEnv.split(File.pathSeparator);
        for (String directory : directories) {
            Path path = Paths.get(directory, command);
            if (Files.isExecutable(path)) return path.toAbsolutePath().toString();
        }
        return null;
    }
}