package com.pokemon.pokemon_backend;

import org.h2.tools.Server; // 💡 H2 도구 모음에서 Server 클래스만 가져옵니다.
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.sql.SQLException;

@Configuration
@Profile("local")
public class H2ServerConfig {

    // 💡 스프링 부트 웹(8080)과 완전히 독립된 8082 포트로 H2 웹 콘솔 서버를 실행합니다!
    @Bean(initMethod = "start", destroyMethod = "stop")
    public Server h2WebServer() throws SQLException {
        System.out.println("🚀 [시스템] 스프링을 우회하는 독립형 H2 웹 콘솔 서버(Port: 8082) 시작 중...");
        return Server.createWebServer("-web", "-webAllowOthers", "-webPort", "8082");
    }
}