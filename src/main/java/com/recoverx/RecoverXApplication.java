package com.recoverx;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.Arrays;
import java.util.List;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RecoverXApplication {

    public static void main(String[] args) {
        // --generate and --classify are batch jobs: skip the web server so the
        // process writes its files and exits instead of hanging on an open port.
        List<String> argList = Arrays.asList(args);
        boolean batch = argList.contains("--generate")
                || argList.contains("--classify")
                || argList.contains("--decide")
                || argList.contains("--execute")
                || argList.contains("--compare");
        WebApplicationType type = batch ? WebApplicationType.NONE : WebApplicationType.SERVLET;

        new SpringApplicationBuilder(RecoverXApplication.class)
                .web(type)
                .run(args);
    }
}
