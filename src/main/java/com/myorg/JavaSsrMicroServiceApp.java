package com.myorg;

import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;

public class JavaSsrMicroServiceApp {
    public static void main(final String[] args) {
        App app = new App();

        new JavaSsrMicroServiceStack(app, "JavaSsrMicroServiceStack", StackProps.builder()
            .build());

        app.synth();
    }

    
}

