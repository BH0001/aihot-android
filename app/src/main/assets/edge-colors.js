(function () {
  // Read only: no bridge, event handlers, page style changes or storage writes.
  if (!document.body || document.readyState === 'loading') return null;
  var canvas = document.createElement('canvas');
  canvas.width = canvas.height = 1;
  var context = canvas.getContext('2d', {willReadFrequently: true});
  function color(value) {
    value = (value || '').trim();
    if (/^#[0-9a-f]{6}$/i.test(value)) return [parseInt(value.slice(1,3),16), parseInt(value.slice(3,5),16), parseInt(value.slice(5,7),16), 1];
    var m = value.match(/^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:\s*[,/]\s*([\d.]+))?\s*\)$/);
    if (m) return [+m[1], +m[2], +m[3], m[4] === undefined ? 1 : +m[4]];
    // Modern CSS color-mix/oklab/srgb values are normalized by the renderer itself.
    if (!context || !value) return null;
    context.clearRect(0,0,1,1);
    context.fillStyle = 'rgba(0,0,0,0)';
    context.fillStyle = value;
    context.fillRect(0,0,1,1);
    var data = context.getImageData(0,0,1,1).data;
    return [data[0],data[1],data[2],data[3]/255];
  }
  function over(fg, bg) {
    return fg ? [fg[0]*fg[3]+bg[0]*(1-fg[3]), fg[1]*fg[3]+bg[1]*(1-fg[3]), fg[2]*fg[3]+bg[2]*(1-fg[3]), 1] : bg;
  }
  function hex(c) {
    return '#' + c.slice(0,3).map(function(v) { return ('0'+Math.round(v).toString(16)).slice(-2); }).join('');
  }
  var root = getComputedStyle(document.documentElement);
  var page = color(root.getPropertyValue('--bg')) || color(getComputedStyle(document.body).backgroundColor);
  if (!page || page[3] === 0) page = [250,249,246,1];
  var surface = color(root.getPropertyValue('--surface'));
  function tone(c) {
    // Keep declared site surfaces exact when compositing introduces tiny 8-bit errors.
    var known = [page, surface];
    for (var i=0; i<known.length; i++) {
      var k=known[i];
      if (k && k[3] === 1 && Math.abs(c[0]-k[0])<=3 &&
          Math.abs(c[1]-k[1])<=3 && Math.abs(c[2]-k[2])<=3) return hex(k);
    }
    return hex(c);
  }
  function edge(y) {
    var element = document.elementFromPoint(1, y), stack = [], result = page;
    while (element) { stack.unshift(element); element = element.parentElement; }
    if (context) {
      // Composite on an opaque page first. Reading transparent canvas pixels first
      // loses precision through premultiplied alpha and can create a visible seam.
      context.globalAlpha = 1;
      context.fillStyle = hex(page);
      context.fillRect(0,0,1,1);
      stack.forEach(function(el) {
        var s = getComputedStyle(el);
        context.globalAlpha = +s.opacity;
        context.fillStyle = 'rgba(0,0,0,0)';
        context.fillStyle = s.backgroundColor;
        context.fillRect(0,0,1,1);
      });
      context.globalAlpha = 1;
      return tone(Array.prototype.slice.call(context.getImageData(0,0,1,1).data));
    }
    stack.forEach(function(el) {
      var s = getComputedStyle(el), c = color(s.backgroundColor);
      if (c) { c[3] *= +s.opacity; result = over(c, result); }
    });
    return tone(result);
  }
  return {url: location.href, page: hex(page), top: edge(1), bottom: edge(innerHeight - 1)};
})()
