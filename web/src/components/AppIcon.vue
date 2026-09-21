<script setup lang="ts">
const props = withDefaults(defineProps<{ name: string; size?: number }>(), { size: 18 })

const paths: Record<string, string[]> = {
  spark: ['M12 3l1.35 4.15L17.5 8.5l-4.15 1.35L12 14l-1.35-4.15L6.5 8.5l4.15-1.35L12 3Z', 'M5 14l.75 2.25L8 17l-2.25.75L5 20l-.75-2.25L2 17l2.25-.75L5 14Z'],
  api: ['M8 4H5a2 2 0 0 0-2 2v3m13-5h3a2 2 0 0 1 2 2v3M8 20H5a2 2 0 0 1-2-2v-3m13 5h3a2 2 0 0 0 2-2v-3', 'M8 12h8M12 8v8'],
  case: ['M6 3h9l3 3v15H6z', 'M15 3v4h4M9 12h6M9 16h4'],
  flow: ['M5 4v10a3 3 0 0 0 3 3h8', 'M13 14l3 3-3 3', 'M5 7a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z'],
  suite: ['M4 5h16v14H4z', 'M4 9h16M8 5v14'],
  env: ['M4 7h16M7 3v4m10-4v4M6 11h5v4H6zm8 0h4v7h-4z'],
  runs: ['M12 3a9 9 0 1 0 9 9', 'M12 7v5l3 2', 'M16 3h5v5'],
  report: ['M5 3h14v18H5z', 'M9 15l2-3 2 2 3-5M9 18h7'],
  settings: ['M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z', 'M4.9 4.9l2 2m10.2-2-2 2m2 10.2-2-2m-8.2 2 2-2M3 12h3m12 0h3M12 3v3m0 12v3'],
  search: ['M11 18a7 7 0 1 1 0-14 7 7 0 0 1 0 14Zm5-2 5 5'],
  play: ['M8 5l11 7-11 7V5Z'],
  plus: ['M12 5v14M5 12h14'],
  chevron: ['m9 18 6-6-6-6'],
  bell: ['M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9', 'M10 21h4'],
  check: ['m5 12 4 4L19 6'],
  close: ['M6 6l12 12M18 6 6 18'],
  code: ['m8 9-4 3 4 3m8-6 4 3-4 3m-2-9-4 12'],
  database: ['M4 6c0-2 3.6-3 8-3s8 1 8 3-3.6 3-8 3-8-1-8-3Z', 'M4 6v6c0 2 3.6 3 8 3s8-1 8-3V6M4 12v6c0 2 3.6 3 8 3s8-1 8-3v-6'],
  more: ['M6 12h.01M12 12h.01M18 12h.01'],
  arrow: ['M5 12h14m-5-5 5 5-5 5'],
  clock: ['M12 3a9 9 0 1 0 9 9 9 9 0 0 0-9-9Z', 'M12 7v5l3 2'],
  filter: ['M4 5h16M7 12h10M10 19h4'],
  copy: ['M8 8h11v11H8z', 'M5 16H4V4h12v1'],
  lock: ['M6 10h12v10H6z', 'M8 10V7a4 4 0 0 1 8 0v3'],
  upload: ['M12 16V4m-4 4 4-4 4 4', 'M5 14v6h14v-6'],
  terminal: ['M5 7l4 5-4 5m7 0h7'],
}
</script>

<template>
  <svg
    class="app-icon"
    :width="props.size"
    :height="props.size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path v-for="path in paths[props.name] ?? paths.case" :key="path" :d="path" />
  </svg>
</template>
