export default function ProductArt({ id }: { id: string }) {
  return (
    <div className={`art art-${id}`} aria-hidden="true">
      <svg viewBox="0 0 240 160" fill="none">
        <ellipse cx="120" cy="133" rx="67" ry="9" fill="black" opacity=".08" />
        {id === "mat" ? (
          <g transform="rotate(-18 120 85)">
            <rect
              x="57"
              y="35"
              width="120"
              height="92"
              rx="13"
              fill="#547763"
            />
            <path
              d="M66 45h100M66 56h100M66 67h100M66 78h100M66 89h100M66 100h100"
              stroke="#82a18e"
              strokeWidth="2"
            />
            <ellipse cx="117" cy="121" rx="60" ry="13" fill="#355843" />
            <ellipse
              cx="117"
              cy="121"
              rx="45"
              ry="7"
              stroke="#81a28c"
              strokeWidth="3"
            />
          </g>
        ) : id === "bottle" ? (
          <g transform="rotate(12 120 80)">
            <rect x="103" y="21" width="34" height="16" rx="5" fill="#282f2c" />
            <rect x="94" y="36" width="52" height="96" rx="19" fill="#ded8ca" />
            <path
              d="M103 54v55"
              stroke="#f9f5e9"
              strokeWidth="6"
              strokeLinecap="round"
            />
            <text
              x="120"
              y="92"
              fill="#48604c"
              textAnchor="middle"
              fontSize="9"
              fontWeight="bold"
            >
              BOLDFIT
            </text>
          </g>
        ) : id === "bands" ? (
          <g strokeWidth="13">
            <ellipse
              cx="95"
              cy="80"
              rx="33"
              ry="48"
              stroke="#ad6846"
              transform="rotate(-30 95 80)"
            />
            <ellipse
              cx="130"
              cy="84"
              rx="33"
              ry="48"
              stroke="#d2a165"
              transform="rotate(8 130 84)"
            />
            <ellipse
              cx="155"
              cy="90"
              rx="28"
              ry="40"
              stroke="#416c51"
              transform="rotate(28 155 90)"
            />
          </g>
        ) : id === "rope" ? (
          <g>
            <path
              d="M82 95C6 6 206 3 180 81S95 160 114 85"
              stroke="#314b3a"
              strokeWidth="5"
            />
            <rect
              x="72"
              y="77"
              width="19"
              height="54"
              rx="7"
              fill="#bd815a"
              transform="rotate(-20 72 77)"
            />
            <rect
              x="105"
              y="78"
              width="19"
              height="54"
              rx="7"
              fill="#bd815a"
              transform="rotate(20 105 78)"
            />
          </g>
        ) : (
          <g transform="rotate(-25 120 80)">
            <rect
              x="60"
              y="45"
              width="120"
              height="73"
              rx="18"
              fill="#576653"
            />
            {[78, 98, 118, 138, 158].map((x) => (
              <path
                key={x}
                d={`M${x} 51v60`}
                stroke="#344b3a"
                strokeWidth="8"
              />
            ))}
            <ellipse cx="176" cy="82" rx="15" ry="36" fill="#84927b" />
            <ellipse cx="176" cy="82" rx="8" ry="25" fill="#334731" />
          </g>
        )}
      </svg>
    </div>
  );
}
