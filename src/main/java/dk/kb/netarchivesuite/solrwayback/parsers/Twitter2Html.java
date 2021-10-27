package dk.kb.netarchivesuite.solrwayback.parsers;

import dk.kb.netarchivesuite.solrwayback.facade.Facade;
import dk.kb.netarchivesuite.solrwayback.properties.PropertiesLoader;
import dk.kb.netarchivesuite.solrwayback.service.dto.ArcEntryDescriptor;
import dk.kb.netarchivesuite.solrwayback.service.dto.ImageUrl;
import dk.kb.netarchivesuite.solrwayback.solr.NetarchiveSolrClient;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class Twitter2Html {
    private static final Logger log = LoggerFactory.getLogger(Twitter2Html.class);
    private static TwitterParser2 parser;

    public static String twitter2Html(String jsonString, String crawlDate) throws Exception{
        parser = new TwitterParser2(jsonString);

        // Get user profile image
        String tweeterProfileImage = parser.isRetweet() ? parser.getRetweetUserProfileImage() : parser.getUserProfileImage();
        List<String> tweeterProfileImageList = Collections.singletonList(tweeterProfileImage);
        ArrayList<ImageUrl> tweeterProfileImageUrl = getImageUrlsFromSolr(tweeterProfileImageList, crawlDate);

        // Get and format tweet text
        String mainTextHtml = formatTweetMainText(parser.getText());

        // Get tweet images
        List<String> tweetImages = new ArrayList<>(parser.getImageUrlsList());
        ArrayList<ImageUrl> tweetImageUrls = getImageUrlsFromSolr(tweetImages, crawlDate);

        String cssFromFile = IOUtils.toString(
                TwitterParser2.class.getClassLoader().getResourceAsStream("twitter_playback_style.css"),
                StandardCharsets.UTF_8);
        String reactionsCss = getReactionsCss();
        String css = cssFromFile + reactionsCss;

        String html =
                "<!DOCTYPE html>"+
                "<html>"+
                "<head>"+
                  "<meta http-equiv='Content-Type' content='text/html;charset=UTF-8'>"+
                  "<meta name='viewport' content='width=device-width, initial-scale=1'>"+
                  "<title>"+getHeadTitle(parser)+"</title>"+
                  "<style>"+css+"</style>"+
                "</head>"+
                "<body>"+
                  "<div id='wrapper'>"+
                    "<div class='tweet'>"+
                      (parser.isRetweet() ? getRetweetHeader(parser, crawlDate) : "")+
                      "<div class='item author'>"+
                        "<div class='user-wrapper'>"+
                          "<a href='"+ (parser.isRetweet() ? makeSolrSearchLink(parser.getRetweetUserScreenName())
                        		   : makeSolrSearchLink("tw_user_id:"+parser.getUserId())) +"'>"+
                            "<span class='avatar'>"+
                              imageUrlToHtml(tweeterProfileImageUrl)+
                            "</span>"+
                            "<div class='user-handles'>"+
                              "<h2>"+ (parser.isRetweet() ? parser.getRetweetUserName() : parser.getUserName()) +"</h2>"+
                              "<h4>@"+ (parser.isRetweet() ? parser.getRetweetUserScreenName() : parser.getUserScreenName()) +"</h4>"+
                            "</div>"+
                          "</a>"+
                          makeUserCard(tweeterProfileImageUrl,
                                  parser.isRetweet() ? parser.getRetweetUserName() : parser.getUserName(),
                                  parser.isRetweet() ? parser.getRetweetUserScreenName() : parser.getUserScreenName(),
                                  parser.isRetweet() ? parser.getRetweetUserDescription() : parser.getUserDescription(),
                                  parser.isRetweet() ? parser.getRetweetUserFriendsCount() : parser.getUserFriendsCount(),
                                  parser.isRetweet() ? parser.getRetweetUserFollowersCount() : parser.getUserFollowersCount())+
                        "</div>"+
                      "</div>"+
                      "<div class='item date'>"+
                        "<div>"+(parser.isRetweet() ? parser.getRetweetCreatedDate() : parser.getCreatedDate())+"</div>"+
                      "</div>"+
                      "<div class='item text'>"+
                        mainTextHtml+
                      "</div>"+
                      (tweetImageUrls.isEmpty() ? "" : "<span class='image'>"+ imageUrlToHtml(tweetImageUrls)) +"</span>"+ // TODO RBKR prettify
                      (parser.hasQuote() ? getQuoteHtml(parser, crawlDate) : "")+
                      "<div class='item reactions'>"+
                        "<span class='icon replies'></span>"+
                        "<span class='number'>"+parser.getReplyCount()+"</span>"+
                        "<span class='icon retweets'></span>"+
                        "<span class='number'>"+parser.getRetweetCount()+"</span>"+
                        "<span class='icon likes'></span>"+
                        "<span class='number'>"+parser.getLikeCount()+"</span>"+
                        "<span class='icon quotes'></span>"+
                        "<span class='number'>"+parser.getQuoteCount()+"</span>"+
                      "</div>"+
                    "</div>"+
                  "</div>"+
                "</body>"+
                "</html>";

        return html;
    }

    private static String formatTweetMainText(String mainText) {
        mainText = formatEntities(mainText);
        mainText = newline2Br(mainText);
        return mainText;
    }

    private static String formatEntities(String text) {
        StringBuilder sb = new StringBuilder(text);

        // Merge hashtags and mentions - TODO RBKR handle urls aswell?
        Map<Pair<Integer, Integer>, String> allEntities = new LinkedHashMap<>();
        allEntities.putAll(parser.getHashtags());
        allEntities.putAll(parser.getMentions());

        // Reverse to insert entities in text from end to start
        List<Pair<Integer, Integer>> reverseKeys = new ArrayList<>(allEntities.keySet());
        Collections.reverse(reverseKeys);
        try {
            for (Pair<Integer, Integer> indexPair : reverseKeys) {
                String tag = allEntities.get(indexPair);
                int startIndex = indexPair.getLeft();
                int endIndex = indexPair.getRight();
                String searchPrefix = tag.charAt(0) == '#' ? "keywords%3A" : ""; // TODO ugly hack for now
                String searchUrl = makeSolrSearchLink(searchPrefix + tag.substring(1));
                String tagWithLink = "<span><a href='" + searchUrl + "'>" + tag + "</a></span>";
                sb.replace(startIndex, endIndex, tagWithLink);
            }
        } catch (Exception e) { // Shouldn't happen
            log.warn("Failed replacing raw tags with solr search links");
        }
        return sb.toString();
    }

    private static ArrayList<ImageUrl> getImageUrlsFromSolr(List<String> imagesList, String crawlDate) throws Exception {
        String imagesSolrQuery = Facade.queryStringForImages(imagesList);
        ArrayList<ArcEntryDescriptor> imageEntries = NetarchiveSolrClient.getInstance()
                .findImagesForTimestamp(imagesSolrQuery, crawlDate);
        return Facade.arcEntrys2Images(imageEntries);
    }

    private static String getReactionsCss() {
        String reactionIconsImageUrl = PropertiesLoader.WAYBACK_BASEURL + "images/twitter_sprite.png";
        return ".item.reactions span.replies {" +
                    "background: transparent url(" + reactionIconsImageUrl + ") no-repeat -145px -50px;}" + // Missing correct icon?
                ".item.reactions span.retweets {" +
                    "background: transparent url(" + reactionIconsImageUrl + ") no-repeat -180px -50px;}" +
                ".item.reactions span.likes {" +
                    "background: transparent url(" + reactionIconsImageUrl + ") no-repeat -145px -130px;}" +
                ".item.reactions span.quotes {" +
                    "background: transparent url(" + reactionIconsImageUrl + ") no-repeat -105px -50px;}"; // Missing correct icon
    }

    private static String makeUserCard(ArrayList<ImageUrl> profileImageUrl, String userName, String userHandle,
                                       String description, int followingCount, int followersCount) {
        return "<div class='user-card'>" +
                    "<div class='item author'>" +
                        "<span class='avatar'>" + imageUrlToHtml(profileImageUrl) + "</span>" +
                        "<div class='user-handles'>" +
                            "<h2>" + userName + "</h2>" +
                            "<h4>@" + userHandle +"</h4>" +
                        "</div>" +
                    "</div>" +
                    "<span class='item user-desc'>" + description + "</span>" +
                    "<div class='follow-info'>" +
                        "<div class='following'>" +
                            "<span class='follow-num'>" + followingCount + "</span>" +
                            "<span> Following</span>" +
                        "</div>" +
                        "<div class='followers'>" +
                            "<span class='follow-num'>" + followersCount + "</span>" +
                            "<span> Followers</span>" +
                        "</div>" +
                    "</div>" +
                "</div>";
    }

    private static String getHeadTitle(TwitterParser2 parser) {
        String titlePrefix = parser.isRetweet() ? "Retweet by: " : "Tweet by: ";
        return titlePrefix + parser.getUserName();
    }


    private static String getQuoteHtml(TwitterParser2 parser, String crawlDate) {
        String quoteHtml = "";

        try {
            List<String> quoteProfileImage = Collections.singletonList(parser.getQuoteUserProfileImage());
            ArrayList<ImageUrl> quoteProfileImageUrl = getImageUrlsFromSolr(quoteProfileImage, crawlDate);
            List<String> quoteImages = new ArrayList<>(parser.getQuoteImageUrlStrings());
            ArrayList<ImageUrl> quoteImageUrls = getImageUrlsFromSolr(quoteImages, crawlDate);

            quoteHtml =
                    "<div class='quote'>" +
                        "<div class='item author'>" +
                            "<div class='user-wrapper'>" +
                                "<a href='" + makeSolrSearchLink(parser.getQuoteUserScreenName()) + "'>" +
                                    "<span class='avatar'>" + imageUrlToHtml(quoteProfileImageUrl) + "</span>" +
                                    "<div class='user-handles'>" +
                                        "<h2>" + parser.getQuoteUserName() + "</h2>" +
                                        "<h4>@" + parser.getQuoteUserScreenName() + "</h4>" +
                                    "</div>" +
                                "</a>" +
                                makeUserCard(quoteProfileImageUrl, parser.getQuoteUserName(),
                                        parser.getQuoteUserScreenName(), parser.getQuoteUserDescription(),
                                        parser.getQuoteUserFriendsCount(), parser.getQuoteUserFollowersCount()) +
                            "</div>" +
                        "</div>" +
                        "<div class='item date'>" +
                            "<div>" + parser.getQuoteCreatedDate() + "</div>" +
                        "</div>" +
                        "<div class='item text'>" +
                            parser.getQuoteText() +
                        "</div>" +
                        (quoteImageUrls.isEmpty() ? "" : "<span class='image'>" + imageUrlToHtml(quoteImageUrls) + "</span>") +
                    "</div>";
        } catch (Exception e) {
            log.warn("Failed getting images for quote in tweet by '{}'", parser.getUserName());
        }
        return quoteHtml;
    }


    private static String newline2Br(String text) {
        if (text == null){
            return "";
        }
        return text.replace("\n","<br>");
    }


    private static String getRetweetHeader(TwitterParser2 parser, String crawlDate) {
        ArrayList<ImageUrl> profileImageUrl = new ArrayList<>();
        try {
            List<String> retweetProfileImage = Collections.singletonList(parser.getUserProfileImage());
            profileImageUrl = getImageUrlsFromSolr(retweetProfileImage, crawlDate);
        } catch (Exception e) {
            log.warn("Failed getting profile image of retweeter with username '{}'", parser.getUserName());
        }
        String html =
                "<div class='retweet-author'>" +
                    "<div class='user-wrapper'>" +
                        "<a href='" + makeSolrSearchLink(parser.getUserScreenName()) + "'>" +
                            "<h3>" + parser.getUserName() + " Retweeted</h3>" +
                        "</a>" +
                        makeUserCard(profileImageUrl, parser.getUserName(),
                                        parser.getUserScreenName(), parser.getUserDescription(),
                                        parser.getUserFriendsCount(), parser.getUserFollowersCount()) +
                    "</div>" +
                    "<div class='date'>&middot " + parser.getCreatedDate() + "</div>" +
                "</div>";
        return html;
    }


    public static String imageUrlToHtml(ArrayList<ImageUrl> images){
        StringBuilder b = new StringBuilder();
        for (ImageUrl image : images){
            b.append("<img src='")
                    .append(image.getDownloadUrl())
                    .append("'/>\n");
        }
        return b.toString();
    }

    private static String makeSolrSearchLink(String searchString) {
        String searchParams = " AND type%3A\"Twitter Tweet\"";
        return PropertiesLoader.WAYBACK_BASEURL + "search?query=" + searchString + searchParams;
    }
}
