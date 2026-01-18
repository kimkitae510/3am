import { useEffect, useRef } from 'react';
import styles from './NightSky.module.css';

interface Star {
  x: number; // 0~1 상대 좌표 — 리사이즈에도 배치가 유지되게
  y: number;
  r: number;
  baseAlpha: number;
  twinkleAmp: number;
  twinkleSpeed: number;
  phase: number;
  color: string;
}

interface ShootingStar {
  x: number;
  y: number;
  vx: number;
  vy: number;
  life: number; // 0~1
}

// 대부분 흰 별, 가끔 푸른 별과 노란 별 (실제 항성 색 온도 흉내)
const STAR_COLORS = ['#eceaf0', '#eceaf0', '#eceaf0', '#eceaf0', '#d6ddff', '#ffe9cd'];

const FRAME_INTERVAL = 1000 / 30;

export function NightSky() {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    let stars: Star[] = [];
    let shooting: ShootingStar | null = null;
    let nextShootingAt = performance.now() + 5000 + Math.random() * 7000;
    let raf = 0;
    let lastFrame = 0;
    let width = 0;
    let height = 0;

    function makeStars() {
      stars = [];
      const count = Math.round((width * height) / 2400); // 390x844 기준 약 137개
      for (let i = 0; i < count; i++) {
        const bright = Math.random() < 0.07;
        stars.push({
          x: Math.random(),
          y: Math.random(),
          r: bright ? 1.1 + Math.random() * 0.7 : 0.3 + Math.random() * 0.7,
          baseAlpha: bright ? 0.75 : 0.2 + Math.random() * 0.5,
          twinkleAmp: 0.12 + Math.random() * 0.25,
          twinkleSpeed: 0.4 + Math.random() * 1.4,
          phase: Math.random() * Math.PI * 2,
          color: STAR_COLORS[Math.floor(Math.random() * STAR_COLORS.length)],
        });
      }
    }

    function resize() {
      const rect = canvas!.getBoundingClientRect();
      const dpr = window.devicePixelRatio || 1;
      width = rect.width;
      height = rect.height;
      canvas!.width = Math.round(width * dpr);
      canvas!.height = Math.round(height * dpr);
      ctx!.setTransform(dpr, 0, 0, dpr, 0, 0);
      makeStars();
      if (reduceMotion) drawFrame(0);
    }

    function spawnShooting() {
      // 위쪽 어딘가에서 시작해 오른쪽 아래로 떨어진다
      const angle = (25 + Math.random() * 20) * (Math.PI / 180);
      const speed = 7 + Math.random() * 4;
      shooting = {
        x: width * (0.1 + Math.random() * 0.7),
        y: height * (0.05 + Math.random() * 0.25),
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed,
        life: 1,
      };
    }

    function drawFrame(t: number) {
      ctx!.clearRect(0, 0, width, height);

      for (const s of stars) {
        const alpha = reduceMotion
          ? s.baseAlpha
          : Math.max(0.05, s.baseAlpha + Math.sin(t * 0.001 * s.twinkleSpeed + s.phase) * s.twinkleAmp);
        const x = s.x * width;
        const y = s.y * height;
        if (s.r > 1) {
          // 밝은 별은 은은한 광륜을 두른다
          const halo = ctx!.createRadialGradient(x, y, 0, x, y, s.r * 5);
          halo.addColorStop(0, `rgba(236, 234, 240, ${alpha * 0.35})`);
          halo.addColorStop(1, 'rgba(236, 234, 240, 0)');
          ctx!.fillStyle = halo;
          ctx!.beginPath();
          ctx!.arc(x, y, s.r * 5, 0, Math.PI * 2);
          ctx!.fill();
        }
        ctx!.globalAlpha = alpha;
        ctx!.fillStyle = s.color;
        ctx!.beginPath();
        ctx!.arc(x, y, s.r, 0, Math.PI * 2);
        ctx!.fill();
        ctx!.globalAlpha = 1;
      }

      if (shooting) {
        const s = shooting;
        const fade = Math.sin(s.life * Math.PI); // 나타났다 사라지는 페이드
        const tailX = s.x - s.vx * 10;
        const tailY = s.y - s.vy * 10;
        const grad = ctx!.createLinearGradient(s.x, s.y, tailX, tailY);
        grad.addColorStop(0, `rgba(236, 234, 240, ${0.85 * fade})`);
        grad.addColorStop(1, 'rgba(236, 234, 240, 0)');
        ctx!.strokeStyle = grad;
        ctx!.lineWidth = 1.4;
        ctx!.lineCap = 'round';
        ctx!.beginPath();
        ctx!.moveTo(s.x, s.y);
        ctx!.lineTo(tailX, tailY);
        ctx!.stroke();

        s.x += s.vx;
        s.y += s.vy;
        s.life -= 0.022;
        if (s.life <= 0 || s.x > width + 80 || s.y > height + 80) {
          shooting = null;
          nextShootingAt = t + 6000 + Math.random() * 10000;
        }
      } else if (!reduceMotion && t >= nextShootingAt) {
        spawnShooting();
      }
    }

    function animate(t: number) {
      if (t - lastFrame >= FRAME_INTERVAL) {
        lastFrame = t;
        drawFrame(t);
      }
      raf = requestAnimationFrame(animate);
    }

    const observer = new ResizeObserver(resize);
    observer.observe(canvas);
    resize();
    if (!reduceMotion) raf = requestAnimationFrame(animate);

    return () => {
      cancelAnimationFrame(raf);
      observer.disconnect();
    };
  }, []);

  return (
    <div className={styles.sky} aria-hidden="true">
      <canvas ref={canvasRef} className={styles.canvas} />
    </div>
  );
}
