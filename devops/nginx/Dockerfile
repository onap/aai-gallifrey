FROM nginx:alpine

COPY ssl-cert-snakeoil.pem /etc/ssl/certs/
COPY ssl-cert-snakeoil.key /etc/ssl/private/
RUN chown -R nginx:nginx /etc/ssl
RUN chmod 640 /etc/ssl/private/ssl-cert-snakeoil.key
RUN chmod 750 /etc/ssl/private

COPY default.conf /etc/nginx/conf.d/
