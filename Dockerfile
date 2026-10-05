# Single-container image: PostgreSQL + MongoDB + Elasticsearch + RabbitMQ +
# Spring Boot API (API + RabbitMQ worker + outbox scheduler in one process) +
# nginx, supervised together.
#
# Build the jar first:  mvn -f backend-java/pom.xml -DskipTests package
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
  && apt-get install -y --no-install-recommends \
    postgresql-16 rabbitmq-server supervisor nginx curl \
    openjdk-17-jre-headless \
  && rm -rf /var/lib/apt/lists \
  && useradd -m -s /bin/bash es

RUN curl -fsSL https://fastdl.mongodb.org/linux/mongodb-linux-x86_64-ubuntu2204-7.0.14.tgz -o /tmp/mongo.tgz \
  && mkdir -p /opt/mongo && tar -xzf /tmp/mongo.tgz -C /opt/mongo --strip-components=1 \
  && rm /tmp/mongo.tgz

RUN curl -fsSL https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-8.13.4-linux-x86_64.tar.gz -o /tmp/es.tgz \
  && mkdir -p /opt/es && tar -xzf /tmp/es.tgz -C /opt/es --strip-components=1 \
  && rm /tmp/es.tgz && chown -R es:es /opt/es

COPY backend-java/target/order-management-1.0.0.jar /app/app.jar
# The schema is Java-owned and also ships inside the jar as a classpath resource.
# It is copied out here so deploy/entrypoint.sh can apply it with psql before
# the JVM starts.
COPY backend-java/src/main/resources/db/001_schema.sql /app/sql/001_schema.sql
COPY frontend/dist /usr/share/nginx/html
COPY deploy/nginx.single.conf /etc/nginx/sites-available/app
COPY deploy/supervisord.conf /etc/supervisor/conf.d/app.conf
COPY deploy/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh \
  && rm -f /etc/nginx/sites-enabled/default \
  && ln -s /etc/nginx/sites-available/app /etc/nginx/sites-enabled/app

VOLUME /data
EXPOSE 8080
ENTRYPOINT ["/entrypoint.sh"]
