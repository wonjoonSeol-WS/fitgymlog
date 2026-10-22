(function () {
  var root = document.querySelector('.fold-carousel');
  if (!root) return;
  var track = root.querySelector('.fold-track');
  var count = track.children.length;
  var controls = root.querySelector('.fold-controls');
  var dots = Array.prototype.slice.call(root.querySelectorAll('.dots button'));
  var pause = root.querySelector('.pause');
  var reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var hovered = false;
  var focused = false;
  var stopped = reduce;

  function current() { return Math.round(track.scrollLeft / track.clientWidth); }
  function go(i) {
    i = (i + count) % count;
    track.scrollTo({ left: i * track.clientWidth, behavior: reduce ? 'auto' : 'smooth' });
  }
  function mark() {
    var at = current();
    dots.forEach(function (d, i) { d.setAttribute('aria-current', i === at ? 'true' : 'false'); });
  }
  function stop() { stopped = true; pause.setAttribute('aria-pressed', 'true'); }

  controls.hidden = false;
  pause.hidden = reduce;
  mark();
  track.addEventListener('scroll', function () { window.requestAnimationFrame(mark); }, { passive: true });
  root.querySelectorAll('[data-step]').forEach(function (b) {
    b.addEventListener('click', function () { go(current() + Number(b.getAttribute('data-step'))); });
  });
  dots.forEach(function (d, i) { d.addEventListener('click', function () { go(i); }); });
  pause.addEventListener('click', function () {
    stopped = pause.getAttribute('aria-pressed') !== 'true';
    pause.setAttribute('aria-pressed', String(stopped));
  });
  track.addEventListener('keydown', function (e) {
    if (e.key === 'ArrowRight') { e.preventDefault(); go(current() + 1); }
    if (e.key === 'ArrowLeft') { e.preventDefault(); go(current() - 1); }
  });
  root.addEventListener('pointerenter', function (e) { if (e.pointerType === 'mouse') hovered = true; });
  root.addEventListener('pointerleave', function (e) { if (e.pointerType === 'mouse') hovered = false; });
  root.addEventListener('touchstart', stop, { passive: true });
  root.addEventListener('focusin', function (e) { focused = e.target.matches(':focus-visible'); });
  root.addEventListener('focusout', function () { focused = false; });
  window.setInterval(function () {
    if (!stopped && !hovered && !focused && !document.hidden) go(current() + 1);
  }, 6000);
})();
