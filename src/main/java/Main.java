import java.util.Scanner;
import java.util.Set;
import java.io.File;
import java.nio.file.Path;

public class Main {

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        Set<String> builtins = Set.of("echo", "exit", "type", "pwd", "cd");

        Path currentDirectory = Path.of(System.getProperty("user.dir"));

        while (true) {

            System.out.print("$ ");

            String input = scanner.nextLine();

            if (input.equals("exit")) {
                break;
            }

            else if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
            }

            else if (input.equals("pwd")) {
                System.out.println(currentDirectory.toAbsolutePath());
            }

            else if (input.startsWith("cd ")) {

                String dirName = input.substring(3);

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

                String command = input.substring(5);

                if (builtins.contains(command)) {

                    System.out.println(command + " is a shell builtin");

                } else {

                    String path = System.getenv("PATH");
                    String[] dirs = path.split(File.pathSeparator);

                    boolean found = false;

                    for (String dir : dirs) {

                        File file = new File(dir, command);

                        if (file.exists() && file.canExecute()) {
                            System.out.println(command + " is " + file.getAbsolutePath());
                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        System.out.println(command + ": not found");
                    }
                }
            }

            else {

                String[] parts = input.split(" ");
                String command = parts[0];

                String path = System.getenv("PATH");
                String[] dirs = path.split(File.pathSeparator);

                File executable = null;

                for (String dir : dirs) {

                    File file = new File(dir, command);

                    if (file.exists() && file.canExecute()) {
                        executable = file;
                        break;
                    }
                }

                if (executable != null) {

                    ProcessBuilder pb = new ProcessBuilder(parts);

                    pb.directory(currentDirectory.toFile());

                    pb.inheritIO();

                    Process process = pb.start();

                    process.waitFor();

                } else {
                    System.out.println(command + ": command not found");
                }
            }
        }
    }
}