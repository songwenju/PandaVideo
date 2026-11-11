"""Film/series metadata and partial-title keyword normalization."""
import re
import unicodedata
from html import unescape
from urllib.parse import urlparse

MEDIA_TYPES = {"all": "全部", "movie": "电影", "tv": "电视剧"}
TV_CATEGORIES = {
    "国产", "国产剧", "香港", "香港剧", "港剧", "台湾", "台湾剧", "台剧",
    "美国", "欧美", "欧美剧", "美剧", "韩国", "韩国剧", "韩剧",
    "日本", "日本剧", "日剧", "海外", "海外剧", "泰国", "泰国剧", "泰剧",
    "连续剧", "电视剧", "短剧", "其他剧",
}
MOVIE_CATEGORIES = {
    "电影", "动作", "喜剧", "爱情", "科幻", "恐怖", "剧情", "战争",
    "惊悚", "悬疑", "犯罪", "纪录", "纪录片", "动画", "动画片", "动画电影",
    "奇幻", "冒险", "家庭", "武侠", "灾难", "音乐", "西部",
    "动作片", "喜剧片", "爱情片", "科幻片", "恐怖片", "剧情片", "战争片",
}


def search_keyword(value):
    text = unicodedata.normalize("NFKC", str(value or "")).strip()
    text = text.replace("《", "").replace("》", "")
    return re.sub(r"(?<=[\u4e00-\u9fff])\s+(?=[\u4e00-\u9fff])", "", text)


def category_type(value):
    category = re.sub(r"\s+", "", str(value or ""))
    if category in TV_CATEGORIES:
        return "tv"
    if category in MOVIE_CATEGORIES:
        return "movie"
    return ""


def infer_media_type(detail_url="", title="", html=""):
    path = urlparse(detail_url).path.lower()
    if re.match(r"^/ju/\d+", path) or re.search(r"/(?:dianshiju|dianshi|lianxuju|tv|dsj)/", path):
        return "tv"
    if re.match(r"^/movie/\d+", path):
        return "movie"
    # Use headings/metadata, never words from a plot or site-wide navigation.
    title = unescape(str(title or ""))
    if re.search(r"电视剧|连续剧|剧集|国产剧|欧美剧|港剧|台剧|韩剧|日剧|短剧|第[一二三四五六七八九十\d]+季|(?:全|更新至)\s*\d+\s*集", title):
        return "tv"
    text = unescape(re.sub(r"<[^>]+>", " ", html))
    if re.search(r"集\s*数\s*[:：]\s*\d+|电视剧名称\s*[:：]", text):
        return "tv"
    if re.search(r"(?:剧情|科幻|动作|喜剧|爱情|战争|恐怖|动画|纪录)片|电影", title):
        return "movie"
    if re.search(r"/(?:dongzuo|xiju|aiqing|kehuanpian|kongbupian|zhanzhengpian|juqingpian)/", path):
        return "movie"
    return ""
