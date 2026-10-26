const nextConfig = {
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${process.env.BACKEND_URL || "http://127.0.0.1:8081"}/api/:path*`,
      },
    ];
  },
};
export default nextConfig;
