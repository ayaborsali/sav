package tn.higer.sav_backend.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Les adresses de la PWA qui ne sont pas des fichiers : le serveur rend
 * `index.html`, et Angular décide de l'écran. `/api/**` n'est jamais concerné.
 */
@Controller
public class PwaController {

    @GetMapping({"/", "/login", "/dashboard", "/photos", "/photos/**", "/reset-password"})
    public String application() {
        return "forward:/index.html";
    }
}
