/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

/**
 * The original picture of a photo Story with music (#38). Facebook plays such a Story as a video
 * made from the photo, and its media model carries both: {@code playable_url}, the video, and
 * {@code stories_photo_uri}, the photo it was made from, whose {@code uri} Facebook shows instead
 * when it reverts the Story to a still (582: the PHOTO_REVERT_P2V card kind). A video Story's media
 * has a {@code stories_video_thumbnail} in its place, and a plain photo Story's has the photo and no
 * video.
 *
 * <p>The story card's kept {@code getMedia} takes the video thumbnail whenever the media has a
 * video, so a save reading it never sees the photo. The media model itself is a field of the card,
 * cached there once {@code getMedia} has run, so it's found among the card's own trees by what it
 * holds, and read through the kept {@code getString} and {@code getTree} by the hash of each field's
 * GraphQL name, as {@link PostDetails} reads a post. No getter Redex renames is used.
 */
final class StoryPhoto {
    static final int STORIES_PHOTO_URI = "stories_photo_uri".hashCode();
    static final int URI = "uri".hashCode();

    private StoryPhoto() {
    }

    /**
     * The address of the photo [card]'s Story was made from, when its media holds both a video and
     * that photo. Null for a video Story, a plain photo Story, a card whose media isn't cached in it
     * or a released tree. Never throws.
     */
    static String originalOf(Object card) {
        for (Object tree : PostDetails.treesIn(card)) {
            if (!PostDetails.isLiveTree(tree) || PostDetails.string(tree, VideoMenuItem.PLAYABLE_URL) == null) continue;
            String uri = PostDetails.string(PostDetails.tree(tree, STORIES_PHOTO_URI), URI);
            if (uri != null && !uri.trim().isEmpty()) return uri;
        }
        return null;
    }
}
