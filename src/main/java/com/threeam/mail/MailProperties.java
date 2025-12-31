package com.threeam.mail;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "mail")
public class MailProperties {

    private String provider = "mock";
    private String from = "";
}
