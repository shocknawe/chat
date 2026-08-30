# Local-demo image: runs the Vite dev server (with HMR and the REST/WS proxy
# to the backend service) inside docker-compose. This is a take-home MVP,
# not a production build — see design.md non-goals.
FROM node:22-alpine

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

ENV VITE_BACKEND_ORIGIN=http://backend:8080
EXPOSE 5173

CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0", "--port", "5173"]
