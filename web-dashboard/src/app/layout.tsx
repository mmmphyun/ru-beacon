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
      <body className="min-h-screen bg-[#090d16] text-slate-100 antialiased selection:bg-blue-600 selection:text-white">
        {children}
      </body>
    </html>
  );
}
