/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.facebook.graphservice.tree.TreeJNI;

import org.junit.Test;

/**
 * The original picture of a photo Story with music, found on the media model the card caches by
 * what it holds: a video and the photo it was made from. A video Story's thumbnail, a video Story
 * with no photo, a plain photo Story, a card with no media cached and a released media tree give
 * nothing.
 */
public class StoryPhotoTest {
    private static final String VIDEO = "https://video.xx.fbcdn.net/v/t42/story_photo_to_video.mp4";
    private static final String PHOTO = "https://scontent.xx.fbcdn.net/v/t39/story_photo_original.jpg";
    private static final String THUMBNAIL = "https://scontent.xx.fbcdn.net/v/t15/story_video_thumbnail.jpg";

    /** A story card as 582's RegularStoryCard holds its trees: its story, then its media once getMedia ran. */
    static final class Card {
        final TreeJNI story;
        final TreeJNI media;
        final String id = "1234567890";

        Card(TreeJNI story, TreeJNI media) {
            this.story = story;
            this.media = media;
        }
    }

    private static TreeJNI story() {
        return new TreeJNI().with("creation_time", 1_756_728_000L);
    }

    private static TreeJNI image(String uri) {
        return new TreeJNI().with("uri", uri);
    }

    /** The ids are the ones 582's media getters load (LX/39C, LX/193;->A0v, RegularStoryCard.getMedia). */
    @Test
    public void theFieldsAreReadByTheIdsFacebooksOwnGettersLoad() {
        assertEquals(0x319918f3, StoryPhoto.STORIES_PHOTO_URI);
        assertEquals(0x1c56c, StoryPhoto.URI);
        assertEquals(0x2cdc643e, VideoMenuItem.PLAYABLE_URL);
    }

    @Test
    public void aPhotoStoryWithMusicGivesTheOriginalPhoto() {
        TreeJNI media = new TreeJNI().with("playable_url", VIDEO).with("stories_photo_uri", image(PHOTO));
        assertEquals(PHOTO, StoryPhoto.originalOf(new Card(story(), media)));
    }

    @Test
    public void aVideoStorysThumbnailIsNotTakenForAPhoto() {
        TreeJNI media = new TreeJNI().with("playable_url", VIDEO).with("stories_video_thumbnail", image(THUMBNAIL));
        assertNull(StoryPhoto.originalOf(new Card(story(), media)));
    }

    @Test
    public void aVideoStoryGivesNothing() {
        assertNull(StoryPhoto.originalOf(new Card(story(), new TreeJNI().with("playable_url", VIDEO))));
    }

    @Test
    public void aPlainPhotoStoryGivesNothing() {
        // getMedia already carries this photo, and the save takes it from there.
        assertNull(StoryPhoto.originalOf(new Card(story(), new TreeJNI().with("stories_photo_uri", image(PHOTO)))));
    }

    @Test
    public void aPhotoWithNoAddressGivesNothing() {
        TreeJNI blank = new TreeJNI().with("playable_url", VIDEO).with("stories_photo_uri", image(" "));
        assertNull(StoryPhoto.originalOf(new Card(story(), blank)));
        TreeJNI none = new TreeJNI().with("playable_url", VIDEO).with("stories_photo_uri", new TreeJNI());
        assertNull(StoryPhoto.originalOf(new Card(story(), none)));
    }

    @Test
    public void aCardWithoutItsMediaGivesNothing() {
        assertNull(StoryPhoto.originalOf(new Card(story(), null)));
        assertNull(StoryPhoto.originalOf(null));
        assertNull(StoryPhoto.originalOf("not a card"));
    }

    @Test
    public void aReleasedMediaTreeIsNeverRead() {
        TreeJNI media = new TreeJNI().with("playable_url", VIDEO).with("stories_photo_uri", image(PHOTO)).releasedTree();
        assertNull(StoryPhoto.originalOf(new Card(story(), media)));
        assertFalse(media.readAfterRelease);
    }
}
