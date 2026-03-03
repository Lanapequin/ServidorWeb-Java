package co.edu.escuelaing.reflexionlab;

@RestController
public class HelloController {

    @GetMapping("/")
    public String index() {
        return "Hola mundo";
    }
}