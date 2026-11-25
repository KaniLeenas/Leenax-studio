// Apply the saved theme before first paint to avoid a flash. Kept external for a strict CSP.
try {
  var t = localStorage.getItem('leenax-theme');
  var dark = t ? t === 'dark' : window.matchMedia('(prefers-color-scheme: dark)').matches;
  if (dark) document.documentElement.classList.add('dark');
} catch (e) {}
