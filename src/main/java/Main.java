import java.util.Scanner;
import java.util.Set;
import java.io.File;

public class Main {

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        Set<String> builtins = Set.of("echo", "exit", "type");

        while (true) {

            System.out.print("$ ");

            String input = scanner.nextLine();

            if (input.equals("exit")) {
                break;
            }

            else if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
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