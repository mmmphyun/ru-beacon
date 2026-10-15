# Ru-Beacon System Design Principles

## Taste Configuration (1-10 Scale)
<!-- Architecture derived from leonxlnx/taste-skill (MIT License, Copyright (c) 2025 Leon Linz) -->
<!-- Global baseline tuned for Ru-Beacon Ruby-Sand Linear Aesthetic -->
- `DESIGN_VARIANCE`: 5   # 1 (Strict/Symmetric) ~ 10 (Asymmetric/Experimental) - Landing: 5, Dashboard: 3
- `VISUAL_DENSITY`: 5    # 1 (Airy/Minimalist) ~ 10 (Information-Dense Cockpit) - Landing: 5, Dashboard: 6
- `MOTION_INTENSITY`: 2  # 1 (Static/Subtle) ~ 10 (Cinematic Spring Physics) - Landing: 2, Dashboard: 3

## 1. Single Source of Truth (SSOT)
- All visual values (colors, spacing, typography, radii) MUST be derived from `.design/tokens/`.
- Surface Hierarchy: Level 0 Canvas (`#141414`), Level 1 Surface/Card (`#1c1c1c`), 1px Warm Sand Hairline (`#32302b`).
- Primary Accent: Ruby Wine (`#d41153`) used with extreme restraint on telemetry badges, status indicators, and text accents.
- Do NOT inject arbitrary hex codes or ad-hoc pixel values into component source code.

## 2. Aesthetic Constraints & Anti-Cliché
- Strict avoidance of generic AI aesthetics (overly rounded cards, ambient neon glows, unsemantic step markers, rotating headlines).
- Destructive status (`#f42e24`) must never fill surfaces; combine with pulse dot, hairline outline, and AlertTriangle icon.
- Focus boldness in a single signature feature per page (e.g. Telemetry Showcase on landing).
