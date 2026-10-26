import "./globals.css";
export const metadata = {
  title: "CredResolve | SmartDialer",
  description:
    "Safe, observable outbound operations with deterministic mock telecom providers.",
};
export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
