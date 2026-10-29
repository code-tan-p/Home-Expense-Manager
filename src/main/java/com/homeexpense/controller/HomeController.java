package com.homeexpense.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Placeholder home page; replaced by the dashboard in a later phase. */
@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "index";
    }
}
