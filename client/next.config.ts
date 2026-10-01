import type { NextConfig } from "next";

const BACKEND_URL = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080";

const nextConfig: NextConfig = {
  async rewrites() {
    return [
      {
        // Proxy all /api/* calls to the Spring Boot backend.
        // This makes the browser think it's calling the same origin (:3000),
        // so SameSite=Lax session cookies are sent correctly.
        source: "/api/:path*",
        destination: `${BACKEND_URL}/api/:path*`,
      },
      {
        // Also proxy the OAuth2 endpoints Spring Security uses
        source: "/oauth2/:path*",
        destination: `${BACKEND_URL}/oauth2/:path*`,
      },
      {
        source: "/login/oauth2/:path*",
        destination: `${BACKEND_URL}/login/oauth2/:path*`,
      },
    ];
  },
};

export default nextConfig;
