import React from "react";

interface RuBeaconLogoProps {
  size?: number;
  className?: string;
}

export function RuBeaconLogo({ size = 24, className = "" }: RuBeaconLogoProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      className={`shrink-0 ${className}`}
      aria-label="Ru-Beacon Logo"
    >
      {/* Outer Hexagon Shell (Ruby Structure Frame) */}
      <polygon
        points="16,3 27,9.5 27,22.5 16,29 5,22.5 5,9.5"
        stroke="#E11D48"
        strokeWidth="2"
        fill="#E11D48"
        fillOpacity="0.1"
      />

      {/* Outer Facet Ambient Shading */}
      <path d="M16 3L27 9.5L20 14L16 11.5L12 14L5 9.5L16 3Z" fill="#CEB28A" fillOpacity="0.25" />
      <path d="M5 9.5L12 14V21L16 23.5V29L5 22.5V9.5Z" fill="#E11D48" fillOpacity="0.25" />
      <path d="M27 9.5L20 14V21L16 23.5V29L27 22.5V9.5Z" fill="#9F1239" fillOpacity="0.35" />

      {/* Inner Solid Floating Beacon Cube */}
      <path d="M16 11.5L20 14L16 16.5L12 14L16 11.5Z" fill="#CEB28A" />
      <path d="M12 14L16 16.5V21.5L12 19V14Z" fill="#E11D48" />
      <path d="M16 16.5L20 14V19L16 21.5V16.5Z" fill="#9F1239" />

      {/* Interlocking Isometric Diamond Keystone Beam */}
      <polygon points="16,3 17.5,3.9 17.5,12.4 16,11.5 14.5,12.4 14.5,3.9" fill="#CEB28A" />
    </svg>
  );
}
