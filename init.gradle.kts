allprojects {
    repositories {
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/google-maven/") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
    }
}
