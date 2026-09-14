"use client";

import React, { useState, useEffect } from "react";

const HEADLINES = [
  {
    prefix: "핫타임 동시 클릭 1,000건 폭격에도",
    highlight: "서버 멈춤 0초",
    suffix: "로 지켜내는 마인크래프트 커뮤니티",
  },
  {
    prefix: "주말 피크 타임 동접 300명 몰려도",
    highlight: "TPS 20.0 절대 사수",
    suffix: "하는 실시간 비동기 OS",
  },
  {
    prefix: "소규모 서버부터 1,000명 대형 네트워크까지",
    highlight: "틱 렉 0%",
    suffix: "의 완전한 디스코드 연동",
  },
];

export function RotatingHeadline() {
  const [index, setIndex] = useState(0);
  const [fade, setFade] = useState(true);

  useEffect(() => {
    const interval = setInterval(() => {
      setFade(false);
      setTimeout(() => {
        setIndex((prev) => (prev + 1) % HEADLINES.length);
        setFade(true);
      }, 400);
    }, 3600);

    return () => clearInterval(interval);
  }, []);

  const current = HEADLINES[index];

  return (
    <div className="min-h-[5.5rem] md:min-h-[7rem] flex items-center justify-center">
      <h1
        className={`text-2xl sm:text-3xl md:text-5xl font-bold tracking-tight text-foreground leading-tight transition-all duration-400 transform ${
          fade ? "opacity-100 translate-y-0" : "opacity-0 -translate-y-1.5"
        }`}
      >
        {current.prefix}
        <br />
        <span className="text-primary underline decoration-primary/40 underline-offset-8">
          {current.highlight}
        </span>
        {current.suffix}
      </h1>
    </div>
  );
}
