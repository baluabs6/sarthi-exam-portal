FROM node:20-alpine AS build
WORKDIR /build
COPY package.json ./
RUN npm install
COPY . .
RUN npm run build

# nginx-unprivileged already runs its worker AND master process as a
# non-root user on an unprivileged port, instead of needing root just to
# bind port 80.
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
