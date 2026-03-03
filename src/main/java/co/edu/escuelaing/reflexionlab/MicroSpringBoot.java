package co.edu.escuelaing.reflexionlab;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

public class MicroSpringBoot {

    private static Map<String, Method> services = new HashMap<>();
    private static Object controllerInstance;

    public static void main(String[] args) throws Exception {

        if (args.length == 0) {
            System.out.println("Debe especificar la clase controlador");
            return;
        }

        // Cargar clase por reflexión
        Class<?> controllerClass = Class.forName(args[0]);

        if (controllerClass.isAnnotationPresent(RestController.class)) {

            controllerInstance = controllerClass.getDeclaredConstructor().newInstance();

            for (Method method : controllerClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(GetMapping.class)) {
                    String path = method.getAnnotation(GetMapping.class).value();
                    services.put(path, method);
                    System.out.println("Servicio cargado: " + path);
                }
            }
        }

        startServer();
    }

    private static void startServer() throws IOException {
        ServerSocket serverSocket = new ServerSocket(35000);
        System.out.println("Servidor iniciado en puerto 35000");

        while (true) {
            Socket clientSocket = serverSocket.accept();
            handleRequest(clientSocket);
        }
    }

    private static void handleRequest(Socket clientSocket) throws IOException {
        var input = clientSocket.getInputStream();
        var output = clientSocket.getOutputStream();

        byte[] buffer = new byte[1024];
        input.read(buffer);

        String request = new String(buffer);
        String path = request.split(" ")[1];

        String responseBody = "404 Not Found";

        if (services.containsKey(path)) {
            try {
                Method method = services.get(path);
                responseBody = (String) method.invoke(controllerInstance);
            } catch (Exception e) {
                responseBody = "500 Internal Server Error";
            }
        }

        String httpResponse =
                "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "\r\n" +
                        responseBody;

        output.write(httpResponse.getBytes());
        output.flush();
        clientSocket.close();
    }
}