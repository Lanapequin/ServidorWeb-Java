package co.edu.escuelaing.reflexionlab;

import java.io.*;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

/**
 * MicroSpringBoot - Mini framework IoC con reflexión y anotaciones.
 *
 * Capacidades:
 * - Escanea el classpath buscando clases con @RestController
 * - Registra métodos anotados con @GetMapping como rutas HTTP
 * - Soporta @RequestParam con defaultValue
 * - Sirve archivos estáticos (HTML, PNG, CSS, JS) desde /webroot
 * - Acepta múltiples solicitudes (no concurrentes)
 *
 * Modos de uso:
 *   1) Auto-scan (recomendado): java -jar reflexionlab.jar
 *   2) Clase explícita:          java -jar reflexionlab.jar co.edu.escuelaing.reflexionlab.HelloController
 */
public class MicroSpringBoot {

    private static final int PORT = 35000;
    private static final String STATIC_DIR = "src/main/resources/webroot";

    // path -> (método, instancia del controlador)
    private static final Map<String, Method>  services  = new HashMap<>();
    private static final Map<String, Object>  instances = new HashMap<>();

    // -------------------------------------------------------------------------
    // Arranque
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            // Modo explícito: se pasa la clase por línea de comandos
            loadController(Class.forName(args[0]));
        } else {
            // Auto-scan: buscar todas las clases con @RestController en el classpath
            scanAndLoad();
        }

        startServer();
    }

    // -------------------------------------------------------------------------
    // Carga de controladores por reflexión
    // -------------------------------------------------------------------------

    /**
     * Registra un controlador en el mapa de servicios usando reflexión.
     */
    private static void loadController(Class<?> clazz) throws Exception {
        if (!clazz.isAnnotationPresent(RestController.class)) {
            System.out.println("AVISO: " + clazz.getName() + " no tiene @RestController, omitiendo.");
            return;
        }

        Object instance = clazz.getDeclaredConstructor().newInstance();

        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(GetMapping.class)) {
                String path = method.getAnnotation(GetMapping.class).value();
                services.put(path, method);
                instances.put(path, instance);
                System.out.println("  [GET] " + path + "  ->  " + clazz.getSimpleName() + "#" + method.getName());
            }
        }
    }

    /**
     * Escanea el directorio de clases compiladas y carga todos los @RestController.
     */
    private static void scanAndLoad() {
        System.out.println("Escaneando classpath en busca de @RestController...");
        File classesDir = new File("target/classes");
        if (!classesDir.exists()) {
            System.out.println("Directorio target/classes no encontrado. Compile primero con: mvn compile");
            return;
        }
        scanDirectory(classesDir, classesDir.getPath());
    }

    private static void scanDirectory(File dir, String basePath) {
        for (File file : dir.listFiles()) {
            if (file.isDirectory()) {
                scanDirectory(file, basePath);
            } else if (file.getName().endsWith(".class")) {
                String className = file.getPath()
                        .replace(basePath + File.separator, "")
                        .replace(File.separator, ".")
                        .replace(".class", "");
                try {
                    Class<?> clazz = Class.forName(className);
                    if (clazz.isAnnotationPresent(RestController.class)) {
                        System.out.println("Encontrado: " + className);
                        loadController(clazz);
                    }
                } catch (Exception e) {
                    // Ignorar clases que no se puedan cargar (anotaciones, interfaces, etc.)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Servidor HTTP
    // -------------------------------------------------------------------------

    private static void startServer() throws IOException {
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("\nServidor MicroSpringBoot iniciado en http://localhost:" + PORT);
        System.out.println("Rutas registradas: " + services.keySet());
        System.out.println("Presiona Ctrl+C para detener.\n");

        while (true) {
            Socket clientSocket = serverSocket.accept();
            handleRequest(clientSocket);
        }
    }

    // -------------------------------------------------------------------------
    // Manejo de solicitudes HTTP
    // -------------------------------------------------------------------------

    private static void handleRequest(Socket clientSocket) throws IOException {
        InputStream  in  = clientSocket.getInputStream();
        OutputStream out = clientSocket.getOutputStream();

        // Leer la solicitud
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.isEmpty()) {
            clientSocket.close();
            return;
        }

        System.out.println(">> " + requestLine);

        // Parsear método y URI
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            clientSocket.close();
            return;
        }

        String rawUri = parts[1];
        URI    uri;
        try {
            uri = new URI(rawUri);
        } catch (Exception e) {
            sendError(out, 400, "Bad Request");
            clientSocket.close();
            return;
        }

        String path  = uri.getPath();
        String query = uri.getRawQuery(); // puede ser null

        // 1) Intentar ruta dinámica (controlador)
        if (services.containsKey(path)) {
            handleServiceRequest(out, path, query);
        }
        // 2) Intentar archivo estático
        else if (serveStaticFile(out, path)) {
            // ya respondido
        }
        // 3) 404
        else {
            sendError(out, 404, "Not Found: " + path);
        }

        out.flush();
        clientSocket.close();
    }

    // -------------------------------------------------------------------------
    // Despacho de servicios dinámicos con @RequestParam
    // -------------------------------------------------------------------------

    private static void handleServiceRequest(OutputStream out, String path, String query) throws IOException {
        Method method   = services.get(path);
        Object instance = instances.get(path);

        try {
            // Parsear query params
            Map<String, String> queryParams = parseQueryParams(query);

            // Construir argumentos del método usando reflexión sobre @RequestParam
            Parameter[] parameters = method.getParameters();
            Object[]    args       = new Object[parameters.length];

            for (int i = 0; i < parameters.length; i++) {
                Parameter param = parameters[i];
                if (param.isAnnotationPresent(RequestParam.class)) {
                    RequestParam rp           = param.getAnnotation(RequestParam.class);
                    String       paramName    = rp.value();
                    String       defaultValue = rp.defaultValue();
                    args[i] = queryParams.getOrDefault(paramName, defaultValue);
                } else {
                    args[i] = null;
                }
            }

            // Invocar el método por reflexión
            String responseBody = (String) method.invoke(instance, args);
            sendResponse(out, 200, "text/html; charset=UTF-8", responseBody.getBytes(StandardCharsets.UTF_8));

        } catch (Exception e) {
            e.printStackTrace();
            sendError(out, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Archivos estáticos
    // -------------------------------------------------------------------------

    private static boolean serveStaticFile(OutputStream out, String path) throws IOException {
        // Mapear "/" a "/index.html para archivos estáticos solo si no hay servicio registrado
        if (path.equals("/")) path = "/index.html";

        File file = new File(STATIC_DIR + path);
        if (!file.exists() || file.isDirectory()) {
            return false;
        }

        String contentType = getContentType(path);
        byte[] content     = Files.readAllBytes(file.toPath());
        sendResponse(out, 200, contentType, content);
        return true;
    }

    private static String getContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".css"))  return "text/css";
        if (path.endsWith(".js"))   return "application/javascript";
        if (path.endsWith(".png"))  return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".ico"))  return "image/x-icon";
        return "application/octet-stream";
    }

    // -------------------------------------------------------------------------
    // Utilidades HTTP
    // -------------------------------------------------------------------------

    private static void sendResponse(OutputStream out, int status, String contentType, byte[] body) throws IOException {
        String statusText = status == 200 ? "OK" : String.valueOf(status);
        String header = "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(body);
    }

    private static void sendError(OutputStream out, int code, String message) throws IOException {
        byte[] body = ("<h1>" + code + " " + message + "</h1>").getBytes(StandardCharsets.UTF_8);
        sendResponse(out, code, "text/html; charset=UTF-8", body);
    }

    /**
     * Parsea una query string del tipo "name=Juan&age=30" en un Map.
     */
    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;

        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                String key   = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                params.put(key, value);
            } else if (kv.length == 1) {
                params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), "");
            }
        }
        return params;
    }
}