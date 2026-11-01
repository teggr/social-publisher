//JAVA 17
//DEPS org.springframework.ai:spring-ai-bom:1.1.8@pom
//DEPS org.springframework.ai:spring-ai-openai
//DEPS org.jsoup:jsoup:1.18.3
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.11
//SOURCES src/main/java/**/*.java
//MAIN dev.socialpublisher.cli.Main

import dev.socialpublisher.cli.Main;

class social_publisher {
  public static void main(String... args) {
    Main.main(args);
  }
}
