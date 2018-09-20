FROM alpine:3.6

# Java Version
ENV JAVA_VERSION_MAJOR 8
ENV JAVA_VERSION_MINOR 131
ENV JAVA_VERSION_BUILD 11
ENV JAVA_PACKAGE       jre
ENV GLIBC_VERSION      2.28-r0

ENV JAVA_8_BASE_URL http://download.oracle.com/otn-pub/java/jdk/${JAVA_VERSION_MAJOR}u${JAVA_VERSION_MINOR}-b${JAVA_VERSION_BUILD}/d54c1d3a095b4ff2b6607d096fa80163/${JAVA_PACKAGE}-${JAVA_VERSION_MAJOR}u${JAVA_VERSION_MINOR}

RUN apk update
RUN apk add curl

# Install glibc (required for java)
RUN apk --no-cache add ca-certificates wget && \
    wget -q -O /etc/apk/keys/sgerrand.rsa.pub https://alpine-pkgs.sgerrand.com/sgerrand.rsa.pub && \
    wget https://github.com/sgerrand/alpine-pkg-glibc/releases/download/${GLIBC_VERSION}/glibc-${GLIBC_VERSION}.apk && \
    apk add glibc-${GLIBC_VERSION}.apk

# Install Java
RUN mkdir /opt && \
    curl -jsSL -H "Cookie: oraclelicense=accept-securebackup-cookie" ${JAVA_8_BASE_URL}-linux-x64.tar.gz | tar -xzf - -C /opt && \
    ln -s /opt/${JAVA_PACKAGE}1.${JAVA_VERSION_MAJOR}.0_${JAVA_VERSION_MINOR} /opt/${JAVA_PACKAGE} && \
    cd /opt/${JAVA_PACKAGE}/ && rm -rf *src.zip lib/missioncontrol lib/visualvm lib/plugin.jar plugin lib/*javafx* lib/*jfx* lib/ext/jfxrt.jar bin/javaws lib/javaws.jar lib/desktop lib/deploy* lib/amd64/libdecora_sse.so lib/amd64/libprism_*.so lib/amd64/libfxplugins.so lib/amd64/libglass.so lib/amd64/libgstreamer-lite.so lib/amd64/libjavafx*.so lib/amd64/libjfx*.

# Install Java Cryptography Extension (JCE) Unlimited Strength
RUN curl -jksSLH "Cookie: oraclelicense=accept-securebackup-cookie" -o /tmp/jce_policy.zip \
    http://download.oracle.com/otn-pub/java/jce/${JAVA_VERSION_MAJOR}/jce_policy-${JAVA_VERSION_MAJOR}.zip && \
    unzip -o -d /opt/${JAVA_PACKAGE}/lib/security /tmp/jce_policy.zip && rm -f /tmp/jce_policy.zip

RUN apk del curl

ENV PATH /opt/jre/bin:${PATH}

RUN apk add zip openssh-keygen openssh-client

EXPOSE 8080

RUN mkdir -p /opt/gallifrey
COPY target/gallifrey-*.jar /opt/gallifrey

COPY ./devops/gallifrey/docker-entrypoint.sh /
RUN chmod 700 /docker-entrypoint.sh
ENTRYPOINT ["/docker-entrypoint.sh"]
