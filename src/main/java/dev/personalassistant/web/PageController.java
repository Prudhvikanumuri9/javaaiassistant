package dev.personalassistant.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public final class PageController {
    @GetMapping({"/overview", "/assistant", "/inventory", "/meals", "/shopping"})
    String appPage() {
        return "forward:/index.html";
    }

    @GetMapping("/admin")
    String adminPage() {
        return "forward:/admin.html";
    }
}
