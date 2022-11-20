FROM openjdk:11.0.8

VOLUME /tmp

ENV JAVA_OPTS="-Xms128m -Xmx1024m"

ADD target/fhir-utility.jar fhir-utility.jar

ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","/fhir-utility.jar"]


