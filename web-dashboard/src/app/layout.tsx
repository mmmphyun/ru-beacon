import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Ru-Beacon Community Dashboard",
  description: "Minecraft & Discord Unified Automation Platform",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko" className="dark">
      <body className="min-h-screen bg-background text-foreground antialiased selection:bg-primary selection:text-primary-foreground">
        {children}
      </body>
    </html>
  );
}
