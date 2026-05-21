package com.pokemon.pokemon_backend;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "*")
public class TestController {
    @GetMapping("/api/ping")
    public String ping(){
        return "서버 응답 성공";
    }
}
