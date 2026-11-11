(() => {
  window.createMediaPoster = ({ title, url, className = "", searchHref, onSearch }) => {
    const frame = document.createElement(searchHref ? "a" : "div");
    frame.className = `media-poster ${className}${searchHref ? " poster-search-link" : ""}`.trim();
    if (searchHref) {
      frame.href = searchHref;
      frame.setAttribute("aria-label", `搜索《${title || "影片"}》资源`);
      frame.title = `搜索《${title || "影片"}》资源`;
      if (onSearch) frame.addEventListener("click", onSearch);
    }
    const fallback = document.createElement("div");
    fallback.className = "poster-fallback";
    fallback.setAttribute("role", "img");
    fallback.setAttribute("aria-label", `${title || "影片"}：暂无海报`);
    const art = document.createElement("span");
    art.className = "poster-film";
    art.setAttribute("aria-hidden", "true");
    if (className.split(/\s+/).includes("result-thumbnail")) {
      const siteIcon = document.createElement("img");
      siteIcon.className = "poster-site-icon";
      siteIcon.src = "/favicon.svg?v=1";
      siteIcon.alt = "";
      siteIcon.width = 48;
      siteIcon.height = 48;
      fallback.append(siteIcon);
    } else {
      fallback.append(art);
    }
    frame.append(fallback);
    if (url || title) {
      const image = document.createElement("img");
      image.alt = `${title || "影片"} 海报`;
      image.loading = "lazy";
      image.decoding = "async";
      image.referrerPolicy = "no-referrer";
      image.style.opacity = "0";
      image.addEventListener("load", () => {
        image.hidden = false;
        image.style.opacity = "1";
        fallback.hidden = true;
      });
      image.addEventListener("error", () => {
        image.hidden = true;
        fallback.hidden = false;
      });
      image.src = `/api/poster?${new URLSearchParams({
        url: url || "", title: title || "",
      })}`;
      frame.append(image);
    }
    return frame;
  };
})();
