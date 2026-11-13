import javax.imageio.ImageIO;
import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * StoryVerse 2 - a gothic personality story game.
 * Four tests (3 stories + 1 mirror quiz), 14 characters, pixel-art 3D-parallax scenes,
 * 4 generated music tracks and bell clicks. No external files needed.
 *
 * Run:  javac StoryVerse.java && java StoryVerse
 *
 * Optional art: put PNGs in ./assets  ->  <character>.png (e.g. vesper.png)
 */
public class StoryVerse {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("StoryVerse");
            GamePanel p = new GamePanel();
            f.setContentPane(p);
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setResizable(false);
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            p.requestFocusInWindow();
        });
    }
}

/* ======================================================================
 *  AUDIO - all sound is generated in code
 * ====================================================================== */
class Audio {
    static final int SR = 44100;
    static final AudioFormat FMT = new AudioFormat(SR, 16, 1, true, false);
    volatile boolean muted = false;
    final Clip[] tracks = new Clip[4];
    volatile int want = 0;
    final byte[] click, choose, hover, thunder;

    Audio() {
        click = pcm(bell(146.8, 1.8, 0.55));                    // deep church bell
        double[] c = bell(73.4, 2.6, 0.7);
        add(c, thump(0.5, 0.9), 0, 1);
        choose = pcm(c);                                         // heavy toll + thump
        hover = pcm(bell(660, 0.5, 0.14));                       // faint chime
        thunder = pcm(makeThunder());
        Thread t = new Thread(() -> { for (int v = 0; v < 4; v++) buildTrack(v); }, "music-gen");
        t.setDaemon(true);
        t.start();
    }

    static double[] bell(double f, double sec, double amp) {
        double[] r = {0.5, 1, 1.2, 1.5, 2, 2.67, 4}, g = {.7, 1, .8, .5, .45, .25, .15};
        int n = (int) (SR * sec);
        double[] o = new double[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR, s = 0;
            for (int k = 0; k < r.length; k++)
                s += g[k] * Math.sin(2 * Math.PI * f * r[k] * t) * Math.exp(-t * (3 + 1.5 * k) / (sec * 0.5));
            o[i] = amp * s / 3.4 * Math.min(1, t * 800);
        }
        return o;
    }

    static double[] thump(double sec, double amp) {
        int n = (int) (SR * sec);
        double[] o = new double[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            ph += 2 * Math.PI * (32 + 50 * Math.exp(-t * 18)) / SR;
            o[i] = amp * Math.sin(ph) * Math.exp(-t * 9);
        }
        return o;
    }

    static double[] pluck(double f, double sec, double amp) {
        int n = (int) (SR * sec);
        double[] p = new double[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            p[i] = (Math.sin(2 * Math.PI * f * t) + .5 * Math.sin(4 * Math.PI * f * t) + .25 * Math.sin(6 * Math.PI * f * t))
                    * Math.exp(-t * 5.0 / sec) * amp * Math.min(1, t * 500);
        }
        return p;
    }

    static double[] makeThunder() {
        int n = (int) (SR * 2.8);
        double[] o = new double[n];
        Random r = new Random(3);
        double y = 0, peak = 1e-9;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            y += (r.nextGaussian() - y) * 0.015;
            o[i] = y * Math.exp(-t * 1.4) * Math.min(1, t * 30);
            peak = Math.max(peak, Math.abs(o[i]));
        }
        for (int i = 0; i < n; i++) o[i] = o[i] / peak * 0.7;
        return o;
    }

    static void add(double[] dst, double[] src, int off, double gain) {
        for (int i = 0; i < src.length; i++) dst[(off + i) % dst.length] += src[i] * gain;   // wraps -> seamless loop
    }

    static byte[] pcm(double[] d) {
        byte[] b = new byte[d.length * 2];
        for (int i = 0; i < d.length; i++) {
            int v = (int) (Math.max(-1, Math.min(1, d[i])) * 32767);
            b[2 * i] = (byte) v;
            b[2 * i + 1] = (byte) (v >> 8);
        }
        return b;
    }

    /** 0 mafia (heartbeat), 1 haunted (music box), 2 vampire waltz, 3 mystic mirror. Each is a 24s loop. */
    static double[] render(int v) {
        double[][][] CH = {
            {{110, 130.81, 164.81}, {87.31, 110, 130.81}, {146.83, 174.61, 220}, {164.81, 207.65, 246.94}},
            {{82.41, 98, 123.47}, {65.41, 82.41, 98}, {110, 130.81, 164.81}, {123.47, 155.56, 185}},
            {{146.83, 174.61, 220}, {116.54, 146.83, 174.61}, {98, 116.54, 146.83}, {110, 138.59, 164.81}},
            {{82.41, 98, 123.47}, {87.31, 110, 130.81}, {82.41, 98, 123.47}, {73.42, 92.5, 110}}};
        double[][] RT = {{55, 43.65, 73.42, 82.41}, {41.2, 32.7, 55, 61.74}, {73.42, 58.27, 49, 55}, {41.2, 43.65, 41.2, 36.71}};
        double[][] chords = CH[v];
        double[] roots = RT[v];
        double chordLen = 6;
        int N = SR * 24;
        double[] m = new double[N];
        Random rnd = new Random(7 + v);
        double padGain = v == 2 ? 0.022 : 0.035;
        for (int c = 0; c < 4; c++) {
            double len = chordLen * 1.5;
            int lenS = (int) (SR * len), st = (int) (SR * c * chordLen);
            for (int i = 0; i < lenS; i++) {
                double t = (double) i / SR;
                double env = Math.pow(Math.sin(Math.PI * t / len), 2);
                double lfo = 1 + 0.004 * Math.sin(2 * Math.PI * 0.2 * t + c);
                double s = Math.sin(2 * Math.PI * roots[c] * t) * 1.6;
                for (double f : chords[c])
                    for (int h = 1; h <= 4; h++)
                        s += (Math.sin(2 * Math.PI * f * h * lfo * t) + Math.sin(2 * Math.PI * f * 1.003 * h * t)) / Math.pow(h, 1.5);
                m[(st + i) % N] += s * env * padGain;
            }
            add(m, bell(chords[c][0] * (v == 1 ? 4 : 2), 5.0, v == 1 ? 0.25 : 0.35), st, 1);
        }
        if (v == 0) {                                                      // mafia heartbeat
            double[] th = thump(0.6, 0.55);
            for (int b = 0; b < 8; b++) { add(m, th, (int) (SR * 3.0 * b), 1); add(m, th, (int) (SR * (3.0 * b + 0.4)), 0.6); }
        }
        if (v == 2) {                                                      // waltz: bass on 1, chords on 2 & 3
            for (int b = 0; b < 12; b++) {
                double t0 = b * 2.0;
                double[] ch = chords[(b / 3) % 4];
                add(m, pluck(roots[(b / 3) % 4] * 2, 1.2, 0.35), (int) (SR * t0), 1);
                for (int beat = 1; beat <= 2; beat++)
                    for (double f : ch) add(m, pluck(f * 2, 0.7, 0.07), (int) (SR * (t0 + beat * 0.667)), 1);
            }
        } else {
            double[] oct = v == 1 ? new double[]{4, 8} : new double[]{2, 4};
            for (int k = 0; k < 16; k++) {
                if (rnd.nextDouble() > (v == 1 ? 0.55 : 0.65)) continue;
                double[] cc = chords[(int) (k * 1.5 / chordLen) % 4];
                add(m, pluck(cc[rnd.nextInt(3)] * oct[rnd.nextInt(2)], 2.5, 0.07), (int) (SR * 1.5 * k), 1);
            }
        }
        double[] e = m.clone();                                            // cathedral echo
        double[] dl = {0.43, 0.71}, dg = {0.35, 0.25};
        for (int d = 0; d < 2; d++) {
            int ds = (int) (SR * dl[d]);
            for (int i = 0; i < N; i++) e[i] += dg[d] * m[(i - ds + N) % N];
        }
        double peak = 1e-9;
        for (double x : e) peak = Math.max(peak, Math.abs(x));
        for (int i = 0; i < N; i++) e[i] = e[i] / peak * 0.85;
        return e;
    }

    void buildTrack(int v) {
        try {
            byte[] data = pcm(render(v));
            Clip c = AudioSystem.getClip();
            c.open(FMT, data, 0, data.length);
            setGain(c, -7f);
            tracks[v] = c;
            if (want == v && !muted) c.loop(Clip.LOOP_CONTINUOUSLY);
        } catch (Throwable ex) {
            System.out.println("[audio] track " + v + " unavailable: " + ex.getMessage());
        }
    }

    void playTrack(int v) {
        want = v;
        for (Clip c : tracks) if (c != null && c.isRunning()) c.stop();
        Clip c = tracks[v];
        if (c != null && !muted) { c.setFramePosition(0); c.loop(Clip.LOOP_CONTINUOUSLY); }
    }

    static void setGain(Clip c, float db) {
        try { ((FloatControl) c.getControl(FloatControl.Type.MASTER_GAIN)).setValue(db); } catch (Exception ignored) { }
    }

    void play(byte[] d, float db) {
        if (muted) return;
        try {
            Clip c = AudioSystem.getClip();
            c.open(FMT, d, 0, d.length);
            setGain(c, db);
            c.addLineListener(ev -> { if (ev.getType() == LineEvent.Type.STOP) c.close(); });
            c.start();
        } catch (Throwable ignored) { }
    }

    void click() { play(click, -4f); }
    void choose() { play(choose, -2f); }
    void hover() { play(hover, -10f); }
    void thunder() { play(thunder, -3f); }

    void toggleMute() {
        muted = !muted;
        if (muted) { for (Clip c : tracks) if (c != null) c.stop(); }
        else playTrack(want);
    }
}

/* ======================================================================
 *  STORY DATA  (plain, easy English)
 * ====================================================================== */
class Choice {
    static final String TR = "SLRKB";        // Smart, Loyal, Ruthless, Kind, Brave
    String text, next;
    int[] fx = new int[5];
    int reqT = -1, reqV;

    Choice(String text, String next, String effects) {
        this.text = text;
        this.next = next;
        for (String tok : effects.split(","))
            if (tok.length() > 1) fx[TR.indexOf(tok.charAt(0))] += Integer.parseInt(tok.substring(1));
    }

    Choice req(char t, int v) { reqT = TR.indexOf(t); reqV = v; return this; }
}

class Scene {
    String id, title, speaker, text;
    int theme;
    Choice[] ch;

    Scene(String id, String title, String speaker, int theme, String text, Choice... ch) {
        this.id = id; this.title = title; this.speaker = speaker; this.theme = theme; this.text = text; this.ch = ch;
    }
}

class Test {
    String title, blurb, start;
    int theme, track;
    String[][] end;          // 5 endings (title, text) in trait order: Smart, Loyal, Ruthless, Kind, Brave

    Test(String title, String blurb, String start, int theme, int track, String[][] end) {
        this.title = title; this.blurb = blurb; this.start = start; this.theme = theme; this.track = track; this.end = end;
    }
}

class Story {
    static final String[] TRAITS = {"Smart", "Loyal", "Ruthless", "Kind", "Brave"};
    static final Color[] TCOL = {new Color(110, 170, 255), new Color(230, 190, 80), new Color(220, 50, 60), new Color(180, 130, 255), new Color(255, 140, 60)};
    static final String[] NOUN = {"Strategist", "Guardian", "Boss", "Healer", "Hero"};
    static final String[] ADJ = {"Clever", "Faithful", "Cold", "Gentle", "Fearless"};
    static final String[] NOUN_DESC = {
        "You think before you act, and you see the whole board.",
        "You protect people and you keep your promises.",
        "You take charge and go after what you want.",
        "You listen, you care, and you make people feel safe.",
        "You step up first when everyone else is afraid."};
    static final String[] SIDE_DESC = {"a sharp, clever mind", "deep loyalty to your people", "a hard, no-nonsense edge", "a warm, gentle heart", "a fearless streak"};

    /** key, name, role, bio, suit, eye, hair, skin, flags (h hat, d hood, l long hair, c cap, a apron, g glasses, f fangs, G ghost, b bald, k cape, s scarf) */
    static final String[][] CAST = {
        {"player", "You", "The main character", "You are the one who makes the choices. Every answer you give shows a little more of who you really are.", "1C1424", "FFC850", "0A0A0E", "C6B2AC", "d"},
        {"vesper", "Vesper", "Family advisor", "Calm and quiet. He has served three bosses and never seems to sleep. Nobody knows what he is thinking.", "282C52", "96D2FF", "BEC3D2", "C6B2AC", ""},
        {"dante", "Dante Voss", "Owner of the Velvet Raven club", "Loud, smiling and dangerous. Half the city owes him a favor, and he never forgets.", "500E16", "FF3C3C", "140A0A", "C09A8C", "hs"},
        {"lucian", "Lucian Hale", "A polite businessman", "He speaks softly and never raises his voice. He always has a plan, and he always smiles.", "103E30", "E6D25A", "281E10", "C6B2AC", "h"},
        {"rosa", "Rosa", "Street kid and spy", "Fast, sharp and fearless. Rosa knows every secret in the alleys of Blackthorn.", "3A2A1A", "FFB040", "5A2E14", "C09A84", "ls"},
        {"marlowe", "Judge Marlowe", "The city judge", "He takes bribes with a smile. He loves money more than the law.", "303030", "A0A0A0", "888888", "C6B0A4", "bg"},
        {"caleb", "Caleb", "Butler of Hollow Manor", "Polite and very pale. He has worked in the manor for a hundred years. Maybe more.", "141416", "C8C8C8", "909090", "D0C6C0", ""},
        {"mira", "Mira", "The maid", "Shy and kind. She is afraid of something in the house, and she is not allowed to say what.", "2C2C44", "8FD0A0", "4A2C1A", "C8A898", "ca"},
        {"thorne", "Dr. Thorne", "The manor doctor", "Friendly and clever. But his smile never reaches his eyes.", "2A4A3A", "9FE0C0", "303030", "C6B2AC", "g"},
        {"isolde", "Isolde", "The ghost in the garden", "A sad spirit. She waited many years for someone brave enough to listen.", "8CA0D8", "B0E0FF", "D8E0FF", "D8E0F0", "lG"},
        {"alaric", "Count Alaric", "Host of the midnight ball", "Charming, old and powerful. He never forgets a face or a promise.", "4A0A14", "FF2020", "140812", "E0D0D0", "klf"},
        {"seraphine", "Seraphine", "The Count's guest", "Graceful and mysterious. She says the truth is the most dangerous gift.", "180C20", "FF6080", "8E1030", "E0CFCF", "lf"},
        {"finn", "Finn", "Young vampire hunter", "Brave, but too quick to fight. He carries a silver knife and a big secret.", "2E4A2E", "70E070", "7A4A20", "C09A84", "s"},
        {"nyx", "Nyx", "Keeper of the Mirror", "Calm and mysterious. She can see your heart before you speak.", "3A1A5A", "E080FF", "0A0612", "C8B8C8", "d"}
    };

    static int dominant(int[] d) {
        int[] order = {2, 4, 0, 3, 1};            // tie-break: Ruthless, Brave, Smart, Kind, Loyal
        int best = order[0];
        for (int i : order) if (d[i] > d[best]) best = i;
        return best;
    }

    static List<Test> tests() {
        List<Test> t = new ArrayList<>();
        t.add(new Test("The Ravenmoor Family", "Your father, a mafia boss, is dead. His secret black book is missing. Who can you trust?", "r1", 0, 0, new String[][]{
            {"The Quiet Mastermind", "{name} never sat on the throne. You ruled from the shadows, and nobody ever knew who pulled the strings."},
            {"The Family Protector", "The family stayed together. Because you kept every promise, people followed you without fear."},
            {"The Iron Boss", "By morning, Blackthorn knew your name. Enemies disappeared, and nobody dared to say no to {name}."},
            {"The Merciful Boss", "You ended the old blood debts. Some people called you weak, but for the first time the city felt safe."},
            {"The Fearless Heir", "{name} walked into every danger first. The city began to say your name with respect, not fear."}}));
        t.add(new Test("The Haunted Manor", "A storm traps you in Hollow Manor. Something is hiding behind the east door.", "h1", 4, 1, new String[][]{
            {"The Clever Guest", "You solved the secret of Hollow Manor and left at sunrise with the whole truth in your pocket."},
            {"The True Friend", "You stayed with Mira until the end. By morning the manor was quiet, and nobody was alone anymore."},
            {"The Cold Survivor", "You did what you had to do to live. The manor let you go, but you felt it watching you leave."},
            {"The Gentle Soul", "You gave Isolde peace at last. She smiled once, and then the whole house felt warm."},
            {"The Ghost Hunter", "You faced the dark and did not blink. Now people tell your story whenever the wind is loud."}}));
        t.add(new Test("Midnight Masquerade", "A vampire count invites you to a grand ball. Not everyone there is who they seem.", "v1", 6, 2, new String[][]{
            {"The Silent Fox", "You tricked both sides and walked out without a scratch. Nobody ever knew whose side you were on."},
            {"The Faithful Friend", "You stood by the people you trusted, even when the night was dark. At dawn they were still beside you."},
            {"The New Count", "You took the bite and the power. The night belongs to you now, and you do not look back."},
            {"The Peacemaker", "You stopped the fight and made both sides listen. For one night, vampires and hunters shared a table."},
            {"The Midnight Hero", "You faced the Count with your head up. Even he bowed to you before the sun came up."}}));
        t.add(new Test("The Mirror of Nyx", "Eight everyday questions. No wrong answers. The mirror shows who you really are.", "n1", 7, 3, new String[][]{
            {"The Thinker", "You solve problems with your mind. You plan first and act later, and that keeps you one step ahead."},
            {"The Guardian", "People feel safe around you. You keep your word, and your friends know you will always show up."},
            {"The Leader", "You go after what you want. You are strong and direct. Just remember to listen to others sometimes."},
            {"The Healer", "You care about how other people feel. Your kindness is your superpower. Just do not forget yourself."},
            {"The Hero", "You act when others freeze. You love a challenge, and you are never afraid to be the first to try."}}));
        return t;
    }

    static Map<String, Scene> build() {
        Map<String, Scene> m = new LinkedHashMap<>();
        // ---------------- TEST 1: THE RAVENMOOR FAMILY ----------------
        a(m, new Scene("r1", "The Funeral", "Narrator", 0,
            "It is raining in Blackthorn. Your father, the boss of the Ravenmoor family, is dead. Everyone at the funeral is watching you. What do you do?",
            c("Light a candle and say goodbye.", "r2", "K1"), c("Stand still and say nothing.", "r2", "R1"),
            c("Look at the crowd. Who looks nervous?", "r2", "S1"), c("Hold Vesper's arm. You need a friend.", "r2", "L1"),
            c("Walk to the front and speak to everyone.", "r2", "B1")));
        a(m, new Scene("r2", "The Missing Book", "Vesper", 4,
            "Boss, your father's black book is gone. It holds every secret in the city. If our enemies get it, we are finished. Where do we start?",
            c("Go to Dante's club.", "r3a", "B1"), c("Invite Lucian to dinner.", "r3b", "S1"),
            c("Ask Rosa, the street kid who sees everything.", "r3c", "K1"),
            c("Make Judge Marlowe talk. I know his secret.", "r4", "S2").req('S', 1)));
        a(m, new Scene("r3a", "The Velvet Raven", "Dante", 1,
            "So you are the new boss. You look just like your father. I did not take his book, but I know where it is. It will cost you.",
            c("Grab him by the collar and make him talk.", "r4", "R2,B1"), c("Pay him what he wants.", "r4", "S1"),
            c("Say: 'Help me, and you will sit at my table.'", "r4", "L1,K1")));
        a(m, new Scene("r3b", "Dinner With Lucian", "Lucian", 6,
            "Please, sit. Try the wine. Your father trusted too many people, you know. Maybe look in the old crypt under the church. Just a friendly tip.",
            c("Secretly switch the glasses when he looks away.", "r4", "S2"), c("Drink the wine. Look him in the eye.", "r4", "B2"),
            c("Smile, then have him followed.", "r4", "S1,R1")));
        a(m, new Scene("r3c", "Rosa Knows", "Rosa", 0,
            "I'm Rosa. I live on these streets. Last night I saw a man carry a box into the old church. I'll tell you more, for a price. Or for a promise.",
            c("Pay her.", "r4", "S1"), c("Promise to look after her.", "r4", "K1,L1"),
            c("Scare her until she talks.", "r4", "R2"), c("Offer her a job in the family.", "r4", "L2")));
        a(m, new Scene("r4", "The Judge's Question", "Marlowe", 4,
            "Everyone in this city has a price, young boss. Tell me honestly. What is yours?",
            c("Money. It makes life easy.", "r5", "R1,S1"), c("Power. I want people to listen.", "r5", "R2"),
            c("A clean conscience. I want to sleep well.", "r5", "K2"), c("Nothing. I am not for sale.", "r5", "L1,B1")));
        a(m, new Scene("r5", "Under the Church", "Narrator", 2,
            "In the dark crypt you find an iron box. Inside is the black book and a letter in Vesper's handwriting. He sold your father to the Cardinal family. Footsteps come down the stairs. It is Vesper, with a gun in his hand.",
            c("Show him the letter. 'Explain.'", "r6", "S1"), c("Point your gun at him first.", "r6", "R2"),
            c("Put your gun down. 'Tell me why.'", "r6", "K2"),
            c("Say nothing. Wait for him to speak.", "r6", "S2").req('S', 3)));
        a(m, new Scene("r6", "The Truth", "Vesper", 2,
            "They took my daughter, boss. Your father knew, and he refused to pay. I chose my child over this family. I would do it again.",
            c("Pull the trigger.", "r7", "R3"), c("Forgive him and take him home.", "r7", "K2,L1"),
            c("Use him. Feed the Cardinals false names.", "r7", "S3"),
            c("Give him the book. Show him you trust him.", "r7", "L3").req('L', 3)));
        a(m, new Scene("r7", "Trouble in the Dark", "Rosa", 0,
            "Boss, look out! A man jumps out of the dark with a knife!",
            c("Fight him with your bare hands.", "r8", "B3"), c("Pull Rosa behind you and protect her.", "r8", "L2,K1"),
            c("Trick him. Shout: 'The police are coming!'", "r8", "S2"), c("Hit him so hard he never gets up again.", "r8", "R3")));
        a(m, new Scene("r8", "Dawn Over Blackthorn", "Narrator", 3,
            "The sun comes up. The church bells ring once for each family. The whole city is waiting to hear what you will do.",
            c("Take the crown. Let the city fear you.", "END", "R1"), c("Disappear into the shadows and rule quietly.", "END", "S1"),
            c("End the old blood debts for good.", "END", "K1"), c("Keep the family together, whatever it costs.", "END", "L1"),
            c("Stand in front of the city and say: 'I am here.'", "END", "B1")));
        // ---------------- TEST 2: THE HAUNTED MANOR ----------------
        a(m, new Scene("h1", "A Stormy Night", "Caleb", 4,
            "Good evening. The storm has closed the road, so you must stay at Hollow Manor tonight. Dinner is at eight. There is one rule: never open the east door.",
            c("Thank him and follow the rules.", "h2", "L1"), c("Ask why the east door is locked.", "h2", "S1"),
            c("Laugh. 'Ghosts are not real.'", "h2", "B1"), c("Ask to leave anyway. This feels wrong.", "h2", "S1,B-1")));
        a(m, new Scene("h2", "Dinner", "Thorne", 4,
            "Eat, eat! Many guests visit Hollow Manor, but few stay long. Oh, ignore the maid, Mira. She is always nervous.",
            c("Smile at Mira. 'Are you okay?'", "h3", "K2"), c("Watch Dr. Thorne carefully.", "h3", "S2"),
            c("Eat quietly and stay out of it.", "h3", "R1"), c("Tell a joke to relax the room.", "h3", "B1,K1")));
        a(m, new Scene("h3", "Midnight Sounds", "Narrator", 4,
            "At midnight you hear whispering inside the walls. Then a soft cry comes from the east side of the house.",
            c("Go and look, alone.", "h4", "B2"), c("Wake Mira and go together.", "h4", "L2"),
            c("Lock the door and wait for daylight.", "h4", "S1"), c("Pull the blanket over your head.", "h4", "S1,B-1")));
        a(m, new Scene("h4", "Mira's Secret", "Mira", 4,
            "Please do not tell the doctor. The east door leads to the garden. I think someone is trapped there. I am too scared to open it. Will you help me?",
            c("Yes. I will open it myself.", "h5", "B2"), c("Yes, but only if you come with me.", "h5", "L2"),
            c("Tell me everything first.", "h5", "S2"), c("No. It is not my problem.", "h5", "R2")));
        a(m, new Scene("h5", "The Lonely Garden", "Isolde", 5,
            "I am Isolde. Dr. Thorne locked my spirit here many years ago, because I found out his secret. Will you help me?",
            c("Of course. I will help you.", "h6", "K2"), c("What secret? Tell me everything.", "h6", "S2"),
            c("What do I get if I help?", "h6", "R2"), c("Do not be afraid. I am not leaving.", "h6", "B1,L1")));
        a(m, new Scene("h6", "The Doctor's Secret", "Thorne", 4,
            "Guests who open that door never leave. Isolde's spirit keeps me young. Yours will do the same.",
            c("Fight him. Knock the lamp out of his hand.", "h7", "B3"), c("Shout for Caleb and Mira. Stand together.", "h7", "L3"),
            c("Trick him. 'The key is in the library.'", "h7", "S3"), c("Make a deal. Free Isolde, and I stay quiet.", "h7", "R3")));
        a(m, new Scene("h7", "Morning", "Narrator", 5,
            "Morning light fills the garden. Isolde's spirit looks calm at last. The storm is gone. What do you say before you leave?",
            c("'Goodbye, Isolde. You are free now.'", "END", "K1"), c("'I will remember what happened here.'", "END", "S1"),
            c("'I am glad I faced it.'", "END", "B1"), c("'Mira, come with me. You are not alone anymore.'", "END", "L1"),
            c("'Never speak of this place again.'", "END", "R1")));
        // ---------------- TEST 3: MIDNIGHT MASQUERADE ----------------
        a(m, new Scene("v1", "A Black Letter", "Narrator", 0,
            "A black letter arrives. 'Come to my ball at midnight. Come alone. - Count Alaric.' Nobody has ever said no to him. What do you do?",
            c("Go proudly. You are not afraid.", "v2", "B2"), c("Find out about the Count first.", "v2", "S2"),
            c("Ask a friend to come with you.", "v2", "L2"), c("Go, but hide something silver in your pocket.", "v2", "R1,S1")));
        a(m, new Scene("v2", "The Ballroom", "Seraphine", 6,
            "Welcome. Everyone here wears a mask. Even me. Would you like a drink? A dance? Or the truth about this place?",
            c("A drink, thank you.", "v3", "B1"), c("A dance.", "v3", "K1"),
            c("The truth, please.", "v3", "S2"), c("No thanks. I will look around alone.", "v3", "R1,S1")));
        a(m, new Scene("v3", "A Secret Warning", "Finn", 6,
            "Psst! I am Finn. I hunt vampires. Everyone here is one! Take this silver knife and help me stop them tonight.",
            c("Take the knife.", "v4", "R2,B1"), c("Say no. Talk to the Count first.", "v4", "K2,S1"),
            c("Ask: 'How do I know you are telling the truth?'", "v4", "S2"), c("Tell Seraphine about Finn.", "v4", "L2")));
        a(m, new Scene("v4", "The Count's Offer", "Alaric", 6,
            "I like you. Live forever. Join us. One small bite is all it takes. Or say no and walk out in peace. It is your choice.",
            c("Accept. Forever sounds good.", "v5", "R3"), c("Refuse. I like being human.", "v5", "K2,L1"),
            c("Offer a deal that helps both sides.", "v5", "S3"), c("Challenge him. Prove who is stronger.", "v5", "B3")));
        a(m, new Scene("v5", "The Fight Begins", "Finn", 6,
            "Finn jumps out with his knife! Seraphine gasps. The guards run in. The whole ballroom freezes. Everyone is looking at you.",
            c("Stand between them and shout 'STOP!'", "v6", "K1,B2"), c("Help Finn. Fight!", "v6", "R2,L1"),
            c("Pull Seraphine to safety.", "v6", "L2,K1"), c("Slip away in the chaos.", "v6", "S2")));
        a(m, new Scene("v6", "Before Sunrise", "Narrator", 3,
            "Sunrise is near. The ball is ending and the candles burn low. What will you remember about tonight?",
            c("That I stayed true to myself.", "END", "L1"), c("That I outsmarted everyone.", "END", "S1"),
            c("That I took what I wanted.", "END", "R1"), c("That I helped when it mattered.", "END", "K1"),
            c("That I was not afraid.", "END", "B1")));
        // ---------------- TEST 4: THE MIRROR OF NYX ----------------
        a(m, new Scene("n1", "The Mirror", "Nyx", 7,
            "Come closer. I am Nyx. My mirror shows who you really are, not who you pretend to be. Answer honestly. There are no wrong answers. Are you ready?",
            c("I am ready.", "n2", "B1"), c("Show me my real self.", "n2", "S1"), c("I am a little scared...", "n2", "K1")));
        a(m, new Scene("n2", "Question 1", "Nyx", 7,
            "You find a wallet full of money on the street. Nobody is watching. What do you do?",
            c("Return all of it.", "n3", "L2,K1"), c("Take the money and throw the wallet away.", "n3", "R2"),
            c("Find the owner and ask for a reward.", "n3", "S2"), c("Give it to the police and walk away.", "n3", "L1,S1")));
        a(m, new Scene("n3", "Question 2", "Nyx", 7,
            "Your best friend lied to you. You just found out. What do you do?",
            c("Say it straight: 'I know you lied.'", "n4", "B2"), c("Say nothing, but watch them closely.", "n4", "S2"),
            c("Stop talking to them forever.", "n4", "R2"), c("Ask why. Maybe they had a reason.", "n4", "K2")));
        a(m, new Scene("n4", "Question 3", "Nyx", 7,
            "A group project is going badly and time is running out. What do you do?",
            c("Take control and give orders.", "n5", "R1,B1"), c("Split the work fairly.", "n5", "L2"),
            c("Quietly fix everyone's mistakes.", "n5", "K1,S1"), c("Find a clever shortcut.", "n5", "S2")));
        a(m, new Scene("n5", "Question 4", "Nyx", 7,
            "You see someone being bullied. What do you do?",
            c("Step in right away.", "n6", "B3"), c("Find a teacher or an adult fast.", "n6", "S1,L1"),
            c("Help the person after it is over.", "n6", "K2"), c("Walk away. It is not your problem.", "n6", "R2")));
        a(m, new Scene("n6", "Question 5", "Nyx", 7,
            "You get a great chance, but you must leave your friends behind. What do you do?",
            c("Go. This is my moment.", "n7", "R1,B1"), c("Stay. Friends matter more.", "n7", "L3"),
            c("Find a way to do both.", "n7", "S2"), c("Ask my friends what they think.", "n7", "K1,L1")));
        a(m, new Scene("n7", "Question 6", "Nyx", 7,
            "Someone is rude to you in public. What do you do?",
            c("Answer back, calmly.", "n8", "B2"), c("Smile, and plan a perfect comeback.", "n8", "S2"),
            c("Ignore it. It is their problem.", "n8", "K1"), c("Remember it. They will pay one day.", "n8", "R2")));
        a(m, new Scene("n8", "Question 7", "Nyx", 7,
            "You have a free weekend and no plans. What do you do?",
            c("Try something new and a little scary.", "n9", "B2"), c("Help someone who needs it.", "n9", "K2"),
            c("Plan how to reach a big goal.", "n9", "S1,R1"), c("Spend time with the people I love.", "n9", "L2")));
        a(m, new Scene("n9", "The Last Question", "Nyx", 7,
            "Last one. Which sentence sounds most like you?",
            c("'I protect my people.'", "END", "L2"), c("'I always find the smart way.'", "END", "S2"),
            c("'I take what I need.'", "END", "R2"), c("'I help when I can.'", "END", "K2"),
            c("'I am not afraid to go first.'", "END", "B2")));
        return m;
    }

    static Choice c(String t, String n, String fx) { return new Choice(t, n, fx); }
    static void a(Map<String, Scene> m, Scene s) { m.put(s.id, s); }
}

/* ======================================================================
 *  GAME PANEL  (everything is drawn with Java2D)
 * ====================================================================== */
class GamePanel extends JPanel implements MouseListener, MouseMotionListener, KeyListener {
    static final int W = 1280, H = 720, PW = 336, PH = 192;
    enum St { MENU, NAME, SELECT, STORY, JOURNAL, TREE, END, PROFILE }

    static final Color GOLD = new Color(212, 170, 80), GOLD_L = new Color(255, 232, 170), PARCH = new Color(232, 218, 188);
    static final Color RED = new Color(200, 20, 30), OUTLINE = new Color(10, 6, 14);

    /** Light glows per theme: {x, y, color, radius} in pixel-layer coordinates. */
    static final int[][][] GLOWS = {
        {{64, 62, 0xFFC860, 110}, {272, 62, 0xFFC860, 110}},
        {{168, 70, 0xFF3060, 170}, {64, 125, 0xFFB050, 60}, {270, 125, 0xFFB050, 60}},
        {{50, 70, 0xFFA040, 120}, {278, 70, 0xFFA040, 120}},
        {{168, 62, 0xFF9A50, 190}},
        {{168, 46, 0xFFC870, 160}, {72, 92, 0xFFC870, 60}},
        {{236, 40, 0xAAB4FF, 170}},
        {{100, 34, 0xFFD890, 120}, {236, 34, 0xFFD890, 120}},
        {{168, 122, 0xA080FF, 130}, {110, 122, 0xFFC060, 70}, {226, 122, 0xFFC060, 70}}};

    class Btn {
        Rectangle r; String label; Runnable act;
        Btn(Rectangle r, String l, Runnable a) { this.r = r; label = l; act = a; }
    }

    final Audio audio = new Audio();
    final Map<String, Scene> scenes = Story.build();
    final List<Test> tests = Story.tests();
    final Map<String, String[]> looks = new LinkedHashMap<>();
    final Map<String, BufferedImage> sprites = new HashMap<>(), imgs = new HashMap<>();
    final Map<Integer, BufferedImage[]> layerCache = new HashMap<>();
    final List<Btn> btns = new ArrayList<>();
    final List<String[]> path = new ArrayList<>();
    final String FONT = pickFont("Cinzel", "Palatino Linotype", "Garamond", "Georgia", "Book Antiqua");

    St st = St.MENU, prevSt = St.MENU;
    String name = "";
    Scene cur;
    int testIdx, shown, tick, endPage, endIdx, jIdx, lastTheme;
    int[] tr = new int[5], base = new int[5];
    boolean[] done = new boolean[4];
    String[] result = new String[4];
    float fade = 1f;
    Point mouse = new Point(-1, -1);
    String hoverKey = "", toast = "";
    int toastT;
    final float[] rx = new float[140], ry = new float[140];

    GamePanel() {
        for (String[] c : Story.CAST) looks.put(c[0], c);
        setPreferredSize(new Dimension(W, H));
        setFocusable(true);
        addMouseListener(this);
        addMouseMotionListener(this);
        addKeyListener(this);
        Random r = new Random();
        for (int i = 0; i < rx.length; i++) { rx[i] = r.nextInt(W); ry[i] = r.nextInt(H); }
        Toolkit.getDefaultToolkit().addAWTEventListener(ev -> {            // every click -> church bell
            if (ev.getID() == MouseEvent.MOUSE_PRESSED) audio.click();
        }, AWTEvent.MOUSE_EVENT_MASK);
        new javax.swing.Timer(33, e -> {
            tick++;
            if (st == St.STORY && cur != null && shown < fmt(cur.text).length()) shown += 2;
            if (fade > 0) fade -= 0.06f;
            if (toastT > 0) toastT--;
            boolean rain = lastTheme == 0 || lastTheme == 5;
            for (int i = 0; i < rx.length; i++) {
                if (rain) { ry[i] += 18; rx[i] -= 3; if (ry[i] > H) { ry[i] = -20; rx[i] = (float) (Math.random() * (W + 200)); } }
                else { ry[i] -= 1.3f; rx[i] += (float) Math.sin(tick * 0.05 + i) * 0.8f; if (ry[i] < 0) { ry[i] = H; rx[i] = (float) (Math.random() * W); } }
            }
            if (st == St.STORY && (lastTheme == 4 || lastTheme == 5) && tick % 300 == 0) audio.thunder();
            repaint();
        }).start();
    }

    /* ---------- helpers ---------- */
    static String pickFont(String... c) {
        Set<String> a = new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String s : c) if (a.contains(s)) return s;
        return Font.SERIF;
    }
    Font font(int style, int size) { return new Font(FONT, style, size); }
    static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a))); }
    static Color hc(int h) { return new Color(h); }
    static int hex(String s) { return Integer.parseInt(s, 16); }
    static Color mixc(int a, int b, double t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return new Color((int) (ar + (br - ar) * t), (int) (ag + (bg - ag) * t), (int) (ab + (bb - ab) * t));
    }
    String fmt(String s) { return s.replace("{name}", name.isEmpty() ? "Heir" : name); }
    int[] delta() { int[] d = new int[5]; for (int i = 0; i < 5; i++) d[i] = tr[i] - base[i]; return d; }

    BufferedImage img(String key) {
        if (!imgs.containsKey(key)) {
            BufferedImage im = null;
            try { File f = new File("assets/" + key + ".png"); if (f.exists()) im = ImageIO.read(f); } catch (Exception ignored) { }
            imgs.put(key, im);
        }
        return imgs.get(key);
    }

    List<String> wrap(FontMetrics fm, String s, int w) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String t = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(t) > w && line.length() > 0) { out.add(line.toString()); line = new StringBuilder(word); }
            else line = new StringBuilder(t);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    void ctr(Graphics2D g, String s, int cx, int y) { g.drawString(s, cx - g.getFontMetrics().stringWidth(s) / 2, y); }
    boolean hovering(Rectangle r) { return r.contains(mouse); }
    Btn addBtn(Rectangle r, String label, Runnable a) { Btn b = new Btn(r, label, a); btns.add(b); return b; }

    /** A raised, bevelled gothic panel (gives the UI a 3D look). */
    void panel(Graphics2D g, int x, int y, int w, int h, int arc, int fillA, boolean glow) {
        g.setColor(new Color(0, 0, 0, 120)); g.fillRoundRect(x + 6, y + 8, w, h, arc, arc);
        g.setPaint(new GradientPaint(0, y, new Color(26, 14, 34, fillA), 0, y + h, new Color(6, 3, 10, fillA)));
        g.fillRoundRect(x, y, w, h, arc, arc);
        g.setStroke(new BasicStroke(2.5f)); g.setColor(glow ? GOLD_L : GOLD); g.drawRoundRect(x, y, w, h, arc, arc);
        g.setStroke(new BasicStroke(1.5f)); g.setColor(new Color(255, 240, 200, 90));
        g.drawLine(x + arc / 2, y + 4, x + w - arc / 2, y + 4);                       // top highlight
        g.setColor(new Color(0, 0, 0, 140));
        g.drawLine(x + arc / 2, y + h - 4, x + w - arc / 2, y + h - 4);              // bottom shade
    }

    void button(Graphics2D g, String label, int cx, int y, int w, int h, Runnable a) {
        Rectangle r = new Rectangle(cx - w / 2, y, w, h);
        addBtn(r, label, a);
        boolean hv = hovering(r);
        int lift = hv ? -3 : 0;
        panel(g, r.x, r.y + lift, w, h, 14, hv ? 240 : 205, hv);
        g.setFont(font(Font.PLAIN, 24));
        g.setColor(hv ? Color.WHITE : GOLD_L);
        ctr(g, label, cx, y + lift + h / 2 + 9);
    }

    void link(Graphics2D g, String label, int x, int y, Runnable a) {
        g.setFont(font(Font.PLAIN, 18));
        int w = g.getFontMetrics().stringWidth(label);
        Rectangle r = new Rectangle(x - 6, y - 20, w + 12, 28);
        addBtn(r, label, a);
        g.setColor(hovering(r) ? Color.WHITE : GOLD);
        g.drawString(label, x, y);
    }

    /* ---------- game flow ---------- */
    void newGame() {
        tr = new int[5]; base = new int[5]; done = new boolean[4]; result = new String[4];
        path.clear(); cur = null; st = St.SELECT; fade = 1f; audio.playTrack(0);
    }

    void openTest(int i) {
        testIdx = i; base = tr.clone(); path.clear();
        goTo(tests.get(i).start); st = St.STORY;
        audio.playTrack(tests.get(i).track);
    }

    void goTo(String id) { cur = scenes.get(id); shown = 0; fade = 1f; }

    void choose(int i) {
        Choice c = cur.ch[i];
        for (int k = 0; k < 5; k++) tr[k] += c.fx[k];
        path.add(new String[]{cur.id, "" + i});
        audio.choose();
        if (c.next.equals("END")) {
            endIdx = Story.dominant(delta()); endPage = 0;
            done[testIdx] = true; result[testIdx] = tests.get(testIdx).end[endIdx][0];
            st = St.END; fade = 1f;
        } else goTo(c.next);
    }

    boolean unlocked(int i) { return i == 0 || done[i - 1]; }
    boolean allDone() { for (boolean b : done) if (!b) return false; return true; }

    void save() {
        try {
            StringBuilder p = new StringBuilder(), r = new StringBuilder(), d = new StringBuilder();
            for (String[] e : path) p.append(e[0]).append(':').append(e[1]).append(';');
            for (int i = 0; i < 4; i++) { r.append(result[i] == null ? "-" : result[i]).append('|'); d.append(done[i] ? 1 : 0).append(','); }
            Files.write(Paths.get("storyverse2.sav"), Arrays.asList(name, "" + testIdx, st == St.STORY && cur != null ? cur.id : "-",
                Arrays.toString(tr).replaceAll("[\\[\\] ]", ""), Arrays.toString(base).replaceAll("[\\[\\] ]", ""), d.toString(), r.toString(), p.toString()));
            toast("Progress saved.");
        } catch (Exception ex) { toast("Could not save."); }
    }

    void load() {
        try {
            List<String> l = Files.readAllLines(Paths.get("storyverse2.sav"));
            name = l.get(0); testIdx = Integer.parseInt(l.get(1));
            String[] t = l.get(3).split(","), b = l.get(4).split(","), d = l.get(5).split(","), r = l.get(6).split("\\|");
            for (int i = 0; i < 5; i++) { tr[i] = Integer.parseInt(t[i]); base[i] = Integer.parseInt(b[i]); }
            for (int i = 0; i < 4; i++) { done[i] = d[i].equals("1"); result[i] = i < r.length && !r[i].equals("-") ? r[i] : null; }
            path.clear();
            if (l.size() > 7) for (String e : l.get(7).split(";")) if (e.contains(":")) path.add(e.split(":"));
            if (l.get(2).equals("-")) { st = St.SELECT; audio.playTrack(0); }
            else { goTo(l.get(2)); st = St.STORY; audio.playTrack(tests.get(testIdx).track); }
            fade = 1f;
        } catch (Exception ex) { toast("No save file found."); }
    }

    void toast(String s) { toast = s; toastT = 70; }

    /* ======================= PAINT ======================= */
    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        btns.clear();
        switch (st) {
            case MENU: drawMenu(g); break;
            case NAME: drawName(g); break;
            case SELECT: drawSelect(g); break;
            case STORY: drawStory(g); break;
            case JOURNAL: drawJournal(g); break;
            case TREE: drawTree(g); break;
            case END: drawEnd(g); break;
            case PROFILE: drawProfile(g); break;
        }
        if (fade > 0) { g.setColor(new Color(0, 0, 0, (int) (255 * Math.min(1, fade)))); g.fillRect(0, 0, W, H); }
        if (toastT > 0) {
            g.setFont(font(Font.PLAIN, 20));
            int w = g.getFontMetrics().stringWidth(toast) + 50;
            panel(g, W / 2 - w / 2, 30, w, 44, 14, 235, false);
            g.setColor(GOLD_L); ctr(g, toast, W / 2, 59);
        }
        g.dispose();
    }

    /* ---------- pixel-art scenery (3 parallax layers per theme) ---------- */
    static void bands(Graphics2D g, int y0, int y1, int c1, int c2) {
        int n = Math.max(2, (y1 - y0) / 5);
        for (int i = 0; i < n; i++) {
            g.setColor(mixc(c1, c2, i / (double) (n - 1)));
            int ya = y0 + (y1 - y0) * i / n, yb = y0 + (y1 - y0) * (i + 1) / n;
            g.fillRect(0, ya, PW, yb - ya + 1);
        }
    }
    /** Checkered floor in perspective. */
    static void tiles(Graphics2D g, int hz, int cA, int cB) {
        int rows = 10;
        for (int k = 0; k < rows; k++) {
            double f0 = Math.pow(k / (double) rows, 1.8), f1 = Math.pow((k + 1) / (double) rows, 1.8);
            int y0 = hz + (int) ((PH - hz) * f0), y1 = hz + (int) ((PH - hz) * f1);
            for (int i = -16; i < 16; i++) {
                int[] xs = {(int) (168 + i * 46 * (f0 + 0.04)), (int) (168 + (i + 1) * 46 * (f0 + 0.04)),
                            (int) (168 + (i + 1) * 46 * (f1 + 0.04)), (int) (168 + i * 46 * (f1 + 0.04))};
                g.setColor(hc(((i + k) & 1) == 0 ? cA : cB));
                g.fillPolygon(xs, new int[]{y0, y0, y1 + 1, y1 + 1}, 4);
            }
        }
    }
    static void column(Graphics2D g, int x, int y0, int y1, int w, int c) {
        int s = Math.max(1, w / 4);
        g.setColor(hc(c)); g.fillRect(x, y0, w, y1 - y0);
        g.setColor(mixc(c, 0xffffff, .25)); g.fillRect(x, y0, s, y1 - y0);
        g.setColor(mixc(c, 0, .5)); g.fillRect(x + w - s, y0, s, y1 - y0);
        g.setColor(mixc(c, 0xffffff, .15)); g.fillRect(x - 2, y0, w + 4, 4); g.fillRect(x - 2, y1 - 4, w + 4, 4);
    }
    static void skyline(Graphics2D g, Random r, int base, int hmin, int hmax, int c, int win, double lit) {
        int x = -4;
        while (x < PW) {
            int w = 14 + r.nextInt(22), h = hmin + r.nextInt(hmax - hmin + 1);
            g.setColor(hc(c)); g.fillRect(x, base - h, w, h + 2);
            for (int yy = base - h + 4; yy < base - 4; yy += 6)
                for (int xx = x + 3; xx < x + w - 3; xx += 5)
                    if (r.nextDouble() < lit) { g.setColor(hc(win)); g.fillRect(xx, yy, 2, 3); }
            x += w + r.nextInt(3);
        }
    }
    static void stars(Graphics2D g, Random r, int n, int y1) {
        for (int i = 0; i < n; i++) { g.setColor(mixc(0x6a6a9a, 0xffffff, r.nextDouble())); g.fillRect(r.nextInt(PW), r.nextInt(y1), 1, 1); }
    }
    static void moon(Graphics2D g, int x, int y, int r) {
        g.setColor(hc(0x2a2a50)); g.fillOval(x - r - 5, y - r - 5, 2 * r + 10, 2 * r + 10);
        g.setColor(hc(0xe8e4d0)); g.fillOval(x - r, y - r, 2 * r, 2 * r);
        g.setColor(hc(0xc8c4b0)); g.fillOval(x - r / 2, y - r / 3, r / 2, r / 2); g.fillOval(x + r / 4, y + r / 4, r / 3, r / 3);
    }
    static void archWin(Graphics2D g, int x, int y, int w, int h, int frame, int glow) {
        g.setColor(hc(glow)); g.fillRect(x, y + w / 2, w, h - w / 2); g.fillArc(x, y, w, w, 0, 180);
        g.setColor(hc(frame)); g.setStroke(new BasicStroke(2));
        g.drawLine(x, y + w / 2, x, y + h); g.drawLine(x + w, y + w / 2, x + w, y + h); g.drawArc(x, y, w, w, 0, 180);
        g.drawLine(x + w / 2, y, x + w / 2, y + h); g.drawLine(x, y + h / 2, x + w, y + h / 2); g.drawLine(x, y + h, x + w, y + h);
    }
    static void torch(Graphics2D g, int x, int y) {
        g.setColor(hc(0x40302a)); g.fillRect(x - 1, y, 3, 9);
        g.setColor(hc(0xff9a30)); g.fillOval(x - 4, y - 9, 8, 10);
        g.setColor(hc(0xffe070)); g.fillOval(x - 2, y - 6, 4, 6);
    }
    static void candle(Graphics2D g, int x, int y) {
        g.setColor(hc(0xe8dcc0)); g.fillRect(x, y, 2, 6);
        g.setColor(hc(0xffd060)); g.fillRect(x, y - 3, 2, 3);
    }
    static void chandelier(Graphics2D g, int x, int y, int w, int c) {
        g.setColor(hc(c)); g.setStroke(new BasicStroke(1));
        g.drawLine(x, 0, x, y); g.fillRect(x - w / 2, y, w, 3); g.fillRect(x - w / 4, y + 3, w / 2, 3);
        for (int i = 0; i <= 4; i++) candle(g, x - w / 2 + i * w / 4 - 1, y - 7);
    }
    static void tomb(Graphics2D g, int x, int y, int w, int h, int c) {
        g.setColor(hc(c)); g.fillRect(x, y + w / 2, w, h - w / 2); g.fillArc(x, y, w, w, 0, 180);
        g.setColor(mixc(c, 0, .5)); g.fillRect(x + w / 2 - 1, y + 5, 2, 8); g.fillRect(x + w / 2 - 3, y + 7, 6, 2);
    }
    static void deadTree(Graphics2D g, Random r, int x, int base, int c) {
        g.setColor(hc(c)); g.setStroke(new BasicStroke(2)); g.fillRect(x, base - 50, 4, 50);
        for (int i = 0; i < 7; i++) {
            int dir = r.nextBoolean() ? 1 : -1, by = base - 14 - i * 6;
            g.drawLine(x + 2, by, x + 2 + dir * (8 + r.nextInt(18)), by - 8 - r.nextInt(10));
        }
    }
    static void lamp(Graphics2D g, int x, int base, int c) {
        g.setColor(hc(c)); g.fillRect(x, base - 62, 3, 66); g.fillRect(x - 4, base - 66, 11, 5);
        g.setColor(hc(0xffd890)); g.fillRect(x - 2, base - 62, 7, 5);
    }
    static void curtains(Graphics2D g, int c, int dark) {
        for (int s = 0; s < 2; s++) {
            int x0 = s == 0 ? 0 : PW - 36;
            for (int i = 0; i < 6; i++) { g.setColor(hc(i % 2 == 0 ? c : dark)); g.fillRect(x0 + i * 6, 0, 6, PH); }
        }
        g.setColor(hc(dark)); g.fillRect(0, 0, PW, 12);
        g.setColor(hc(0xd0a040)); g.fillRect(0, 12, PW, 2);
    }

    BufferedImage[] layers(int th) { return layerCache.computeIfAbsent(th, this::makeLayers); }

    BufferedImage[] makeLayers(int th) {
        BufferedImage[] L = new BufferedImage[3];
        Graphics2D[] G = new Graphics2D[3];
        for (int i = 0; i < 3; i++) { L[i] = new BufferedImage(PW, PH, BufferedImage.TYPE_INT_ARGB); G[i] = L[i].createGraphics(); }
        Graphics2D a = G[0], b = G[1], c = G[2];
        Random r = new Random(th * 31 + 7);
        switch (th) {
            case 0: // night city street
                bands(a, 0, 125, 0x080820, 0x3a2858); stars(a, r, 70, 100); moon(a, 262, 38, 13);
                a.setColor(hc(0x120e1a)); a.fillRect(0, 125, PW, 67);
                skyline(a, r, 125, 25, 60, 0x151232, 0x6a5a2a, 0.25);
                skyline(b, r, 122, 45, 95, 0x0b0a1a, 0xffc860, 0.45);
                tiles(b, 122, 0x1c1828, 0x171320);
                b.setColor(hc(0x7a8ad0)); for (int i = 0; i < 14; i++) b.fillRect(20 + r.nextInt(290), 135 + r.nextInt(50), 8 + r.nextInt(18), 1);
                lamp(b, 64, 122, 0x1a1520); lamp(b, 272, 122, 0x1a1520);
                c.setColor(hc(0x07050c)); c.fillRect(0, 26, 12, 166); c.fillRect(PW - 12, 26, 12, 166);
                c.fillRect(0, 22, 24, 6); c.fillRect(PW - 24, 22, 24, 6);
                break;
            case 1: // club
                bands(a, 0, PH, 0x2c0a12, 0x10040a);
                a.setColor(hc(0x1a0810)); a.fillRect(100, 62, 136, 60);
                a.setColor(new Color(255, 80, 120, 45)); a.fillPolygon(new int[]{168, 110, 226}, new int[]{0, 122, 122}, 3);
                a.setColor(new Color(255, 200, 120, 35)); a.fillPolygon(new int[]{168, 60, 140}, new int[]{0, 122, 122}, 3);
                a.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13)); a.setColor(hc(0xff3060)); a.drawString("VELVET RAVEN", 103, 50);
                curtains(a, 0x7a1020, 0x4a0a18);
                tiles(b, 120, 0x2a0c14, 0x1e0810);
                for (int x : new int[]{64, 270}) {
                    b.setColor(hc(0x2a1008)); b.fillRect(x - 2, 134, 4, 30);
                    b.setColor(hc(0x6a3a22)); b.fillOval(x - 24, 126, 48, 14); b.setColor(hc(0x8a5a3a)); b.fillOval(x - 24, 126, 48, 6);
                    candle(b, x - 1, 120);
                }
                c.setColor(hc(0x0a0306));
                c.fillPolygon(new int[]{0, 0, 52, 78}, new int[]{192, 146, 168, 192}, 4);
                c.fillPolygon(new int[]{PW, PW, PW - 52, PW - 78}, new int[]{192, 146, 168, 192}, 4);
                c.setColor(hc(0xd0a040)); c.fillRect(24, 146, 3, 46); c.fillOval(21, 142, 9, 9);
                c.fillRect(PW - 27, 146, 3, 46); c.fillOval(PW - 30, 142, 9, 9);
                break;
            case 2: // crypt
                a.setColor(hc(0x1a1c24)); a.fillRect(0, 0, PW, PH);
                for (int y = 0; y < PH; y += 8) for (int x = -8; x < PW; x += 16) {
                    a.setColor(hc((((x / 16) + (y / 8)) & 1) == 0 ? 0x2a2c36 : 0x242630)); a.fillRect(x + (y / 8 % 2) * 8, y, 15, 7);
                }
                for (int x : new int[]{36, 143, 250}) archWin(a, x, 40, 50, 80, 0x3a3c48, 0x06060a);
                a.setColor(hc(0x5a5c68));
                for (int x : new int[]{20, 120, 215, 310}) for (int y = 0; y < 40; y += 4) a.fillRect(x + (y / 4 % 2), y, 2, 3);
                tiles(b, 120, 0x2a2c36, 0x20222a);
                b.setColor(hc(0x4a4c58)); b.fillRect(114, 102, 108, 20); b.setColor(hc(0x6a6c78)); b.fillRect(114, 102, 108, 3);
                column(b, 58, 20, 130, 14, 0x3a3c48); column(b, PW - 72, 20, 130, 14, 0x3a3c48);
                torch(b, 50, 70); torch(b, 278, 70);
                column(c, -4, 0, PH, 26, 0x14151b); column(c, PW - 22, 0, PH, 26, 0x14151b);
                c.setColor(new Color(200, 200, 210, 120));
                for (int i = 0; i < 6; i++) { c.drawLine(22, 0, 22 + i * 7, 24 + i * 5); c.drawLine(PW - 22, 0, PW - 22 - i * 7, 24 + i * 5); }
                break;
            case 3: // cathedral at dawn
                a.setColor(hc(0x241a2a)); a.fillRect(0, 0, PW, PH);
                for (int y = 0; y < PH; y += 10) { a.setColor(hc(0x2c2032)); a.drawLine(0, y, PW, y); }
                archWin(a, 26, 24, 30, 100, 0x4a3a50, 0xe8903a); archWin(a, 280, 24, 30, 100, 0x4a3a50, 0xe8903a);
                a.setColor(hc(0x120a18)); a.fillOval(120, 18, 96, 96);
                int[] gl = {0xe8803a, 0xd04060, 0x7a4ac0, 0x3a7ad0, 0xe8c040, 0x40b080};
                for (int i = 0; i < 12; i++) { a.setColor(hc(gl[i % 6])); a.fillArc(124, 22, 88, 88, i * 30, 30); }
                a.setColor(hc(0x1a1020)); a.setStroke(new BasicStroke(2)); a.drawOval(124, 22, 88, 88); a.drawOval(146, 44, 44, 44);
                for (int i = 0; i < 12; i++) a.drawLine(168, 66, 168 + (int) (44 * Math.cos(i * Math.PI / 6)), 66 - (int) (44 * Math.sin(i * Math.PI / 6)));
                a.setColor(hc(0xe8c040)); a.fillOval(158, 56, 20, 20);
                tiles(b, 118, 0x4a3a46, 0x362a34);
                for (int k = 0; k < 4; k++) {
                    int y = 126 + k * 15;
                    b.setColor(hc(0x1c1218)); b.fillRect(24 - k * 6, y, 88, 8); b.fillRect(24 - k * 6, y - 6, 88, 3);
                    b.fillRect(PW - 112 + k * 6, y, 88, 8); b.fillRect(PW - 112 + k * 6, y - 6, 88, 3);
                }
                b.setColor(hc(0x5a4a56)); b.fillRect(138, 98, 60, 22); b.setColor(hc(0x7a6a76)); b.fillRect(138, 98, 60, 3);
                candle(b, 144, 90); candle(b, 188, 90);
                b.setColor(new Color(255, 210, 150, 34));
                b.fillPolygon(new int[]{26, 56, 150, 90}, new int[]{50, 50, 190, 190}, 4);
                b.fillPolygon(new int[]{280, 310, 250, 200}, new int[]{50, 50, 190, 190}, 4);
                column(c, -4, 0, PH, 28, 0x1c1420); column(c, PW - 24, 0, PH, 28, 0x1c1420);
                break;
            case 4: // manor hall
                for (int x = 0; x < PW; x += 12) { a.setColor(hc((x / 12) % 2 == 0 ? 0x2a1620 : 0x321a26)); a.fillRect(x, 0, 12, PH); }
                a.setColor(hc(0x1c0e12)); a.fillRect(0, 122, PW, 70); a.setColor(hc(0x4a2a20)); a.fillRect(0, 120, PW, 3);
                for (int[] f : new int[][]{{30, 50, 34, 44}, {272, 50, 34, 44}, {104, 38, 28, 36}, {204, 38, 28, 36}}) {
                    a.setColor(hc(0x8a6a30)); a.fillRect(f[0] - 3, f[1] - 3, f[2] + 6, f[3] + 6);
                    a.setColor(hc(0x14202a)); a.fillRect(f[0], f[1], f[2], f[3]);
                    a.setColor(hc(0x2a3a4a)); a.fillOval(f[0] + f[2] / 2 - 6, f[1] + 6, 12, 14); a.fillRect(f[0] + f[2] / 2 - 9, f[1] + 22, 18, 14);
                }
                archWin(a, 140, 30, 56, 92, 0x6a5030, 0x1a2a5a);
                tiles(b, 124, 0x3a2418, 0x2e1a10);
                b.setColor(hc(0x3a2010)); b.fillRect(54, 64, 22, 62); b.setColor(hc(0xd8c890)); b.fillOval(58, 68, 14, 14);
                b.setColor(hc(0x14080a)); b.fillRect(64, 72, 1, 6); b.fillRect(64, 77, 4, 1);
                for (int i = 0; i < 6; i++) { b.setColor(hc(i % 2 == 0 ? 0x2a1a12 : 0x3a261a)); b.fillRect(236 + i * 16, 132 - i * 12, 100, 12); }
                chandelier(b, 168, 40, 64, 0xc0a050);
                curtains(c, 0x4a0a14, 0x300810);
                break;
            case 5: // graveyard
                bands(a, 0, 130, 0x0a0e28, 0x40305a); stars(a, r, 80, 100); moon(a, 236, 40, 20);
                deadTree(a, r, 52, 130, 0x070810); deadTree(a, r, 284, 130, 0x070810); deadTree(a, r, 20, 130, 0x070810);
                a.setColor(hc(0x14141e)); a.fillRect(140, 86, 56, 46); a.fillPolygon(new int[]{136, 168, 200}, new int[]{88, 66, 88}, 3);
                a.setColor(hc(0x050508)); a.fillRect(160, 104, 16, 28);
                a.setColor(hc(0x0c1612)); a.fillRect(0, 130, PW, 62);
                bands(b, 126, PH, 0x10201a, 0x060c08);
                b.setColor(hc(0x1a1a22));
                for (int x = 0; x < PW; x += 8) { b.fillRect(x, 104, 1, 24); b.fillRect(x - 1, 102, 3, 3); }
                b.fillRect(0, 112, PW, 2);
                for (int i = 0; i < 7; i++) tomb(b, 16 + i * 46 + r.nextInt(14), 112 + r.nextInt(14), 16 + r.nextInt(8), 24 + r.nextInt(8), 0x3a3e50);
                c.setColor(hc(0x050806)); c.fillRect(0, 0, 16, PH); c.fillRect(PW - 16, 0, 16, PH);
                c.setStroke(new BasicStroke(3));
                for (int i = 0; i < 5; i++) { c.drawLine(16, 30 + i * 12, 40 + r.nextInt(20), 20 + i * 10); c.drawLine(PW - 16, 40 + i * 12, PW - 40 - r.nextInt(20), 30 + i * 10); }
                c.setColor(hc(0x06100a));
                for (int x = 0; x < PW; x += 3) c.fillRect(x, PH - 6 - r.nextInt(10), 2, 16);
                break;
            case 6: // ballroom
                bands(a, 0, PH, 0x4a3418, 0x1e140a);
                for (int i = 0; i < 5; i++) archWin(a, 22 + i * 64, 22, 30, 92, 0xb08a3a, 0x1a2250);
                a.setColor(hc(0xb08a3a)); a.fillRect(0, 118, PW, 3);
                tiles(b, 116, 0x6a626e, 0x26202c);
                column(b, 40, 0, 122, 16, 0xb09050); column(b, PW - 56, 0, 122, 16, 0xb09050);
                chandelier(b, 100, 30, 56, 0xd0b060); chandelier(b, 236, 30, 56, 0xd0b060);
                curtains(c, 0x3a0a3a, 0x240624);
                break;
            default: // 7: mystic room
                bands(a, 0, PH, 0x07041a, 0x1e0e38); stars(a, r, 140, PH);
                a.setColor(hc(0x6a3a9a)); a.setStroke(new BasicStroke(2)); a.drawOval(108, 18, 120, 120); a.drawOval(120, 30, 96, 96);
                for (int i = 0; i < 5; i++) {
                    int j = (i * 2) % 5;
                    a.drawLine(168 + (int) (48 * Math.cos(i * 1.2566 - 1.57)), 78 + (int) (48 * Math.sin(i * 1.2566 - 1.57)),
                               168 + (int) (48 * Math.cos(j * 1.2566 - 1.57)), 78 + (int) (48 * Math.sin(j * 1.2566 - 1.57)));
                }
                tiles(b, 128, 0x1c1232, 0x150c26);
                b.setColor(hc(0x6a3a9a)); b.setStroke(new BasicStroke(2)); b.drawOval(60, 150, 216, 30);
                b.setColor(hc(0x3a1a60)); b.fillOval(98, 134, 140, 26); b.fillRect(98, 146, 140, 14);
                b.setColor(hc(0x9a70ff)); b.fillOval(158, 110, 20, 20); b.setColor(hc(0xe0d0ff)); b.fillRect(162, 114, 4, 4);
                candle(b, 108, 124); candle(b, 224, 124);
                curtains(c, 0x24083c, 0x14042a);
                break;
        }
        for (Graphics2D x : G) x.dispose();
        return L;
    }

    int[] off(int k) {
        double mx = mouse.x < 0 ? 0 : (mouse.x / (double) W - 0.5) * 2, my = mouse.y < 0 ? 0 : (mouse.y / (double) H - 0.5) * 2;
        double sw = Math.sin(tick * 0.02) * 0.35;
        int d = new int[]{6, 14, 26}[k];
        return new int[]{(int) (-(mx * 0.8 + sw) * d), (int) (-my * d * 0.35)};
    }

    void drawLayer(Graphics2D g, int th, int k) {
        int[] o = off(k);
        Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(layers(th)[k], -32 + o[0], -24 + o[1], PW * 4, PH * 4, null);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old == null ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : old);
    }

    /** Back + middle layers, glows. Characters are drawn after this, then bgFront(). */
    void bgBack(Graphics2D g, int th) {
        lastTheme = th;
        drawLayer(g, th, 0);
        drawLayer(g, th, 1);
        int[] o = off(1);
        for (int i = 0; i < GLOWS[th].length; i++) {
            int[] gl = GLOWS[th][i];
            float cx = gl[0] * 4 - 32 + o[0], cy = gl[1] * 4 - 24 + o[1], rad = gl[3];
            float fl = (float) (0.75 + 0.25 * Math.sin(tick * 0.31 + i * 2.1) * Math.sin(tick * 0.13 + i));
            g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), rad, new float[]{0, 1},
                new Color[]{alpha(hc(gl[2]), (int) (95 * fl)), alpha(hc(gl[2]), 0)}));
            g.fillOval((int) (cx - rad), (int) (cy - rad), (int) (rad * 2), (int) (rad * 2));
        }
    }

    void bgFront(Graphics2D g, int th) {
        if (th == 2 || th == 5) {                                       // drifting fog
            for (int i = 0; i < 4; i++) {
                int x = (int) ((tick * (0.6 + i * 0.2) + i * 400) % (W + 600)) - 300;
                g.setPaint(new RadialGradientPaint(new Point2D.Float(x, 620 + i * 20), 300, new float[]{0, 1},
                    new Color[]{new Color(180, 190, 220, 40), new Color(180, 190, 220, 0)}));
                g.fillOval(x - 300, 620 + i * 20 - 90, 600, 180);
            }
        }
        drawLayer(g, th, 2);
        boolean rain = th == 0 || th == 5;
        for (int i = 0; i < rx.length; i++) {
            if (rain) { g.setColor(new Color(170, 190, 255, 55)); g.setStroke(new BasicStroke(1.2f)); g.drawLine((int) rx[i], (int) ry[i], (int) rx[i] - 6, (int) ry[i] + 22); }
            else { g.setColor(new Color(255, 190, 100, 120)); g.fillRect((int) rx[i], (int) ry[i], 3, 3); }
        }
        if (th == 4 || th == 5) {                                       // lightning
            int ft = tick % 300;
            if (ft < 3) { g.setColor(new Color(220, 230, 255, 90)); g.fillRect(0, 0, W, H); }
            else if (ft >= 6 && ft < 8) { g.setColor(new Color(220, 230, 255, 50)); g.fillRect(0, 0, W, H); }
        }
        g.setPaint(new RadialGradientPaint(new Point2D.Float(W / 2f, H / 2f), 820, new float[]{0.45f, 1},
            new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 190)}));
        g.fillRect(0, 0, W, H);
    }

    /* ---------- pixel-art characters ---------- */
    void og(Graphics2D g, Shape s, Paint p) {
        g.setPaint(p); g.fill(s); g.setColor(OUTLINE); g.setStroke(new BasicStroke(3f)); g.draw(s);
    }

    BufferedImage sprite(String key) {
        BufferedImage cached = sprites.get(key);
        if (cached != null) return cached;
        String[] L = looks.getOrDefault(key, looks.get("player"));
        int suit = hex(L[4]), eye = hex(L[5]), hair = hex(L[6]), skin = hex(L[7]);
        String f = L[8];
        boolean hat = f.contains("h"), hood = f.contains("d"), lng = f.contains("l"), cap = f.contains("c"), gl = f.contains("g"),
                fang = f.contains("f"), ghost = f.contains("G"), bald = f.contains("b"), cape = f.contains("k"), scarf = f.contains("s"), apron = f.contains("a");
        BufferedImage im = new BufferedImage(144, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        g.translate(72, 196); g.scale(0.38, 0.38);
        if (ghost) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.68f));
        Color eyeC = hc(eye);
        if (cape) og(g, new Polygon(new int[]{-215, -130, 130, 215}, new int[]{150, -280, -280, 150}, 4), mixc(suit, 0, .25));
        if (lng) og(g, new Ellipse2D.Double(-80, -445, 160, 330), hc(hair));
        og(g, new RoundRectangle2D.Double(-175, -250, 350, 400, 130, 130),
            new GradientPaint(-175, 0, mixc(suit, 0xffffff, .2), 175, 0, mixc(suit, 0, .45)));
        if (!hood && !ghost && !apron) og(g, new Polygon(new int[]{-42, 42, 0}, new int[]{-250, -250, -110}, 3), hc(0xe6e6ea));
        if (!lng && !cape && !ghost && !apron && !hood && !scarf) og(g, new Polygon(new int[]{-8, 8, 12, 0, -12}, new int[]{-235, -235, -160, -130, -160}, 5), eyeC);
        if (apron) og(g, new Polygon(new int[]{-62, 62, 80, -80}, new int[]{-215, -215, 150, 150}, 4), hc(0xe8e4dc));
        if (cape) {
            og(g, new Polygon(new int[]{-30, -100, -88, -30}, new int[]{-300, -372, -262, -240}, 4), mixc(suit, 0xffffff, .12));
            og(g, new Polygon(new int[]{30, 100, 88, 30}, new int[]{-300, -372, -262, -240}, 4), mixc(suit, 0, .2));
        }
        g.setColor(mixc(skin, 0, .35)); g.fillRect(-24, -295, 48, 55);
        if (scarf) {
            og(g, new RoundRectangle2D.Double(-50, -282, 100, 36, 20, 20), mixc(eye, 0, .35));
            og(g, new Polygon(new int[]{12, 44, 52, 20}, new int[]{-250, -250, -170, -170}, 4), mixc(eye, 0, .35));
        }
        if (hood) {
            og(g, new Ellipse2D.Double(-92, -475, 184, 270), mixc(suit, 0, .3));
            og(g, new Ellipse2D.Double(-46, -400, 92, 118), mixc(skin, 0, .55));
        } else {
            Ellipse2D head = new Ellipse2D.Double(-48, -410, 96, 124);
            og(g, head, hc(skin));
            g.setClip(head); g.setColor(new Color(0, 0, 0, 60)); g.fill(new Ellipse2D.Double(12, -420, 80, 140)); g.setClip(null);
            g.setColor(OUTLINE); g.setStroke(new BasicStroke(3f)); g.draw(head);
            if (!bald && !cap) og(g, new Arc2D.Double(-52, -420, 104, 90, 0, 180, Arc2D.CHORD), hc(hair));
            if (lng) { og(g, new Ellipse2D.Double(-68, -400, 28, 150), hc(hair)); og(g, new Ellipse2D.Double(40, -400, 28, 150), hc(hair)); }
        }
        if (cap) {
            og(g, new Arc2D.Double(-52, -420, 104, 90, 0, 180, Arc2D.CHORD), hc(hair));
            og(g, new RoundRectangle2D.Double(-54, -432, 108, 34, 30, 30), hc(0xf0f0f0));
            g.setColor(OUTLINE); g.fillRect(-54, -402, 108, 4);
        }
        if (hat) {
            og(g, new Ellipse2D.Double(-96, -398, 192, 34), hc(0x0e0e12));
            og(g, new RoundRectangle2D.Double(-56, -468, 112, 80, 40, 40), hc(0x16161c));
            g.setColor(mixc(eye, 0, .4)); g.fillRect(-55, -408, 110, 12);
        }
        for (int sx = -1; sx <= 1; sx += 2) {
            g.setPaint(new RadialGradientPaint(new Point2D.Float(sx * 20, -346), 24, new float[]{0, 1}, new Color[]{alpha(eyeC, 170), alpha(eyeC, 0)}));
            g.fillOval(sx * 20 - 24, -370, 48, 48);
            g.setColor(eyeC); g.fill(new Ellipse2D.Double(sx * 20 - 9, -352, 18, 10));
            g.setColor(OUTLINE); g.fillRect(sx * 20 - 2, -350, 5, 6);
        }
        if (gl) { g.setColor(new Color(220, 220, 230)); g.setStroke(new BasicStroke(3f)); g.draw(new Ellipse2D.Double(-42, -366, 32, 32)); g.draw(new Ellipse2D.Double(10, -366, 32, 32)); g.drawLine(-10, -352, 10, -352); }
        g.setColor(new Color(70, 30, 36)); g.setStroke(new BasicStroke(3f)); g.drawLine(-12, -308, 12, -310);
        if (fang) { g.setColor(Color.WHITE); g.fillPolygon(new int[]{-10, -3, -7}, new int[]{-309, -309, -294}, 3); g.fillPolygon(new int[]{3, 10, 7}, new int[]{-309, -309, -294}, 3); }
        g.dispose();
        sprites.put(key, im);
        return im;
    }

    void drawChar(Graphics2D g, String key, int cx, double phase) {
        String[] L = looks.getOrDefault(key, looks.get("player"));
        boolean ghost = L[8].contains("G");
        double mx = mouse.x < 0 ? 0 : (mouse.x / (double) W - 0.5) * 2;
        int bob = (int) (Math.sin(tick * 0.06 + phase) * (ghost ? 9 : 3));
        int x = cx - 216 + (int) (-mx * 14), y = H + 8 - 600 + bob + (ghost ? -24 : 0);
        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, y + 190), 300, new float[]{0, 1},
            new Color[]{alpha(hc(hex(L[5])), 55), alpha(hc(hex(L[5])), 0)}));
        g.fillOval(cx - 300, y - 110, 600, 600);
        g.setColor(new Color(0, 0, 0, 90)); g.fillOval(cx - 150, H - 40, 300, 50);       // ground shadow
        BufferedImage as = img(key);
        Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        if (as != null) { int h = 600, w = h * as.getWidth() / as.getHeight(); g.drawImage(as, cx - w / 2, H - h, w, h, null); }
        else g.drawImage(sprite(key), x, y, 432, 600, null);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old == null ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : old);
    }

    /* ---------- MENU / NAME / SELECT ---------- */
    void drawTitle(Graphics2D g, String t, int cx, int y, int size) {
        g.setFont(font(Font.BOLD, size));
        int w = g.getFontMetrics().stringWidth(t);
        for (int i = 10; i > 0; i--) { g.setColor(mixc(0x3a2808, 0x8a6020, 1 - i / 10.0)); g.drawString(t, cx - w / 2 + i / 2, y + i); }   // extrusion
        g.setPaint(new GradientPaint(0, y - size, GOLD_L, 0, y, new Color(190, 140, 50)));
        g.drawString(t, cx - w / 2, y);
    }

    void drawMenu(Graphics2D g) {
        bgBack(g, 0);
        drawChar(g, "vesper", 190, 0);
        drawChar(g, "seraphine", 1090, 2);
        bgFront(g, 0);
        drawTitle(g, "StoryVerse", W / 2, 200, 104);
        g.setFont(font(Font.ITALIC, 24)); g.setColor(new Color(215, 195, 165));
        ctr(g, "Four tests. One story. Find out who you really are.", W / 2, 258);
        button(g, "Play", W / 2, 320, 300, 54, () -> { name = ""; st = St.NAME; });
        button(g, "Continue", W / 2, 388, 300, 54, this::load);
        button(g, "Music: " + (audio.muted ? "OFF" : "ON"), W / 2, 456, 300, 54, audio::toggleMute);
        button(g, "Exit", W / 2, 524, 300, 54, () -> System.exit(0));
        g.setFont(font(Font.ITALIC, 16)); g.setColor(new Color(170, 150, 130));
        ctr(g, "Move the mouse to look around. Every click tolls a bell.", W / 2, 680);
    }

    void drawName(Graphics2D g) {
        bgBack(g, 7);
        drawChar(g, "nyx", 1080, 1);
        bgFront(g, 7);
        g.setFont(font(Font.BOLD, 40)); g.setColor(GOLD_L);
        ctr(g, "What is your name?", W / 2, 240);
        panel(g, W / 2 - 250, 290, 500, 70, 16, 225, false);
        g.setFont(font(Font.PLAIN, 34)); g.setColor(Color.WHITE);
        ctr(g, name + ((tick / 12) % 2 == 0 ? "|" : ""), W / 2, 338);
        button(g, "Begin", W / 2, 410, 260, 54, () -> {
            if (!name.trim().isEmpty()) { name = name.trim(); newGame(); } else toast("Please type your name first.");
        });
        button(g, "Back", W / 2, 480, 260, 54, () -> st = St.MENU);
        g.setFont(font(Font.ITALIC, 18)); g.setColor(new Color(170, 150, 130));
        ctr(g, "Type your name and press Enter.", W / 2, 590);
    }

    void drawSelect(Graphics2D g) {
        bgBack(g, 7);
        bgFront(g, 7);
        g.setColor(new Color(0, 0, 0, 140)); g.fillRect(0, 0, W, H);
        drawTitle(g, "Choose a Test", W / 2, 90, 58);
        int n = 0; for (boolean b : done) if (b) n++;
        g.setFont(font(Font.ITALIC, 22)); g.setColor(new Color(215, 195, 165));
        ctr(g, "Welcome, " + name + ".  Tests finished: " + n + " / 4", W / 2, 128);
        for (int i = 0; i < tests.size(); i++) {
            final int k = i;
            Test t = tests.get(i);
            boolean open = unlocked(i);
            Rectangle r = new Rectangle(60 + i * 295, 160, 270, 340);
            if (open) addBtn(r, t.title, () -> openTest(k));
            boolean hv = hovering(r) && open;
            int lift = hv ? -8 : 0;
            panel(g, r.x, r.y + lift, r.width, r.height, 16, 235, hv);
            Shape oc = g.getClip();
            g.setClip(new RoundRectangle2D.Double(r.x + 8, r.y + lift + 8, r.width - 16, 130, 10, 10));
            Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(layers(t.theme)[0], r.x + 8, r.y + lift + 8, r.width - 16, 130, null);
            g.drawImage(layers(t.theme)[1], r.x + 8, r.y + lift + 8, r.width - 16, 130, null);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old == null ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : old);
            if (!open) { g.setColor(new Color(0, 0, 0, 170)); g.fillRect(r.x, r.y, r.width, 150); }
            g.setClip(oc);
            g.setFont(font(Font.BOLD, 21)); g.setColor(open ? GOLD_L : new Color(130, 120, 110));
            int ty = r.y + lift + 172;
            for (String ln : wrap(g.getFontMetrics(), (i + 1) + ". " + t.title, r.width - 30)) { g.drawString(ln, r.x + 16, ty); ty += 24; }
            g.setFont(font(Font.PLAIN, 15)); g.setColor(open ? new Color(225, 215, 200) : new Color(120, 110, 100));
            ty = r.y + lift + 220;
            for (String ln : wrap(g.getFontMetrics(), t.blurb, r.width - 32)) { g.drawString(ln, r.x + 16, ty); ty += 20; }
            g.setFont(font(Font.ITALIC, 16));
            if (done[i]) { g.setColor(new Color(150, 220, 150)); g.drawString("\u2714 " + result[i], r.x + 16, r.y + lift + r.height - 18); }
            else if (!open) {
                g.setColor(new Color(190, 120, 110)); g.drawString("Locked. Finish test " + i + " first.", r.x + 16, r.y + lift + r.height - 18);
                g.setColor(new Color(170, 160, 150)); g.fillRoundRect(r.x + r.width / 2 - 14, r.y + 62, 28, 24, 6, 6);
                g.setStroke(new BasicStroke(4)); g.drawArc(r.x + r.width / 2 - 10, r.y + 40, 20, 30, 0, 180);
            } else { g.setColor(GOLD); g.drawString("New. Click to play.", r.x + 16, r.y + lift + r.height - 18); }
        }
        button(g, "Journal", 330, 560, 200, 50, () -> { prevSt = St.SELECT; st = St.JOURNAL; });
        button(g, "My Profile", 560, 560, 200, 50, () -> st = St.PROFILE);
        button(g, "Save", 790, 560, 200, 50, this::save);
        button(g, "Main Menu", 1020, 560, 200, 50, () -> st = St.MENU);
        if (allDone()) { g.setFont(font(Font.BOLD, 22)); g.setColor(GOLD_L); ctr(g, "All tests done! Open \"My Profile\" for your final result.", W / 2, 650); }
    }

    /* ---------- STORY ---------- */
    Color nameColor(String speaker) {
        if (speaker.equals("Narrator")) return GOLD_L;
        String[] L = looks.get(speaker.split(" ")[0].toLowerCase());
        return L == null ? GOLD_L : mixc(hex(L[5]), 0xffffff, .35);
    }

    void drawStory(Graphics2D g) {
        bgBack(g, cur.theme);
        boolean narr = cur.speaker.equals("Narrator");
        String key = cur.speaker.split(" ")[0].toLowerCase();
        drawChar(g, "player", narr ? W / 2 : 215, 0);
        if (!narr) drawChar(g, key, 1065, 1.7);
        bgFront(g, cur.theme);

        String full = fmt(cur.text);
        boolean isDone = shown >= full.length();
        panel(g, 60, 530, 1160, 150, 22, 232, false);
        panel(g, 90, 494, 260, 44, 12, 245, false);
        g.setFont(font(Font.BOLD, 24)); g.setColor(nameColor(cur.speaker)); ctr(g, cur.speaker, 220, 525);
        g.setFont(font(Font.PLAIN, 25));
        FontMetrics fm = g.getFontMetrics();
        int left = shown, y = 580;
        g.setColor(new Color(245, 237, 220));
        for (String ln : wrap(fm, full, 1090)) {
            if (left <= 0) break;
            g.drawString(ln.substring(0, Math.min(ln.length(), left)), 100, y);
            left -= ln.length() + 1; y += 34;
        }
        if (isDone) drawChoices(g);

        link(g, "Journal", 640, 703, () -> { prevSt = St.STORY; st = St.JOURNAL; });
        link(g, "Branches", 740, 703, () -> { prevSt = St.STORY; st = St.TREE; });
        link(g, "Save", 860, 703, this::save);
        link(g, "Music " + (audio.muted ? "Off" : "On"), 930, 703, audio::toggleMute);
        link(g, "Tests", 1070, 703, () -> { st = St.SELECT; audio.playTrack(0); });
        g.setFont(font(Font.ITALIC, 15));
        int[] d = delta();
        int x = 80;
        for (int i = 0; i < 5; i++) {
            g.setColor(Story.TCOL[i]);
            String s = Story.TRAITS[i] + " " + d[i];
            g.drawString(s, x, 703);
            x += g.getFontMetrics().stringWidth(s) + 18;
        }
    }

    void drawChoices(Graphics2D g) {
        int n = cur.ch.length, bh = 56, gap = 12, total = n * (bh + gap) - gap;
        int y0 = Math.max(30, (470 - total) / 2), x = 340, w = 600;
        int[] d = delta();
        g.setColor(new Color(0, 0, 0, 165)); g.fillRoundRect(x - 36, y0 - 22, w + 72, total + 44, 22, 22);
        for (int i = 0; i < n; i++) {
            Choice c = cur.ch[i];
            boolean locked = c.reqT >= 0 && d[c.reqT] < c.reqV;
            final int idx = i;
            Rectangle r = new Rectangle(x, y0 + i * (bh + gap), w, bh);
            addBtn(r, c.text, locked ? null : () -> choose(idx));
            boolean hv = hovering(r) && !locked;
            int lift = hv ? -3 : 0;
            g.setColor(new Color(0, 0, 0, 120)); g.fillRoundRect(r.x + 4, r.y + 6, w, bh, 12, 12);
            g.setPaint(new GradientPaint(0, r.y + lift, hv ? new Color(130, 24, 36, 245) : new Color(70, 12, 22, 235), 0, r.y + lift + bh, new Color(22, 4, 10, 245)));
            g.fillRoundRect(r.x, r.y + lift, w, bh, 12, 12);
            g.setColor(locked ? new Color(100, 90, 80) : hv ? GOLD_L : GOLD);
            g.setStroke(new BasicStroke(hv ? 2.5f : 1.6f)); g.drawRoundRect(r.x, r.y + lift, w, bh, 12, 12);
            g.setColor(new Color(255, 230, 190, locked ? 25 : 70)); g.drawLine(r.x + 10, r.y + lift + 4, r.x + w - 10, r.y + lift + 4);
            int fs = 21;
            g.setFont(font(Font.PLAIN, fs));
            while (g.getFontMetrics().stringWidth(c.text) > w - 110 && fs > 13) g.setFont(font(Font.PLAIN, --fs));
            g.setColor(locked ? new Color(130, 120, 110) : hv ? Color.WHITE : GOLD_L);
            ctr(g, c.text, r.x + w / 2, r.y + lift + bh / 2 + 7);
            if (c.reqT >= 0) {
                g.setFont(font(Font.ITALIC, 13));
                g.setColor(locked ? new Color(200, 130, 120) : new Color(140, 190, 140));
                g.drawString("Needs: " + Story.TRAITS[c.reqT] + " " + c.reqV, r.x + w - 130, r.y + lift + bh - 7);
            }
            if (locked) {
                g.setColor(new Color(150, 140, 130)); g.fillRoundRect(r.x + w - 38, r.y + 20, 18, 15, 4, 4);
                g.setStroke(new BasicStroke(2.5f)); g.drawArc(r.x + w - 36, r.y + 10, 14, 18, 0, 180);
            }
        }
    }

    /* ---------- JOURNAL (character book) ---------- */
    void drawJournal(Graphics2D g) {
        g.setPaint(new GradientPaint(0, 0, new Color(16, 6, 10), 0, H, Color.BLACK)); g.fillRect(0, 0, W, H);
        g.setFont(font(Font.PLAIN, 54)); g.setColor(Color.WHITE);
        g.drawString("\u2767 Characters \u2767", 90, 90);
        Rectangle xr = new Rectangle(1190, 60, 56, 56);
        addBtn(xr, "close", () -> st = prevSt);
        g.setColor(RED); g.fillRect(xr.x, xr.y, 56, 70);
        g.setColor(Color.WHITE); g.setStroke(new BasicStroke(3));
        g.drawLine(xr.x + 16, xr.y + 16, xr.x + 40, xr.y + 40); g.drawLine(xr.x + 40, xr.y + 16, xr.x + 16, xr.y + 40);
        int cnt = Story.CAST.length;
        button(g, "\u25C0", 56, 330, 60, 60, () -> jIdx = (jIdx + cnt - 1) % cnt);
        button(g, "\u25B6", W - 56, 330, 60, 60, () -> jIdx = (jIdx + 1) % cnt);
        g.setColor(new Color(50, 40, 36)); g.fillRoundRect(100, 150, 1080, 530, 10, 10);
        g.setColor(PARCH); g.fillRect(112, 160, 520, 510); g.fillRect(648, 160, 520, 510);
        g.setColor(RED); g.fillRect(630, 155, 20, 520);
        String[] p = Story.CAST[jIdx];
        g.setColor(Color.WHITE); g.fillRect(250, 190, 220, 240);
        Shape oc = g.getClip();
        g.setClip(260, 200, 200, 200);
        g.setPaint(new GradientPaint(0, 200, new Color(50, 36, 70), 0, 400, new Color(8, 6, 12))); g.fillRect(260, 200, 200, 200);
        Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(sprite(p[0]), 260, 200, 460, 400, 22, 8, 122, 108, null);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old == null ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : old);
        g.setClip(oc);
        g.setFont(font(Font.ITALIC, 34)); g.setColor(new Color(90, 60, 40));
        ctr(g, jIdx == 0 && !name.isEmpty() ? name : p[1], 360, 480);
        g.setFont(font(Font.PLAIN, 21)); g.setColor(new Color(60, 44, 36));
        ctr(g, p[2], 360, 516);
        g.setFont(font(Font.PLAIN, 24)); g.setColor(new Color(50, 36, 30));
        int yy = 230;
        for (String ln : wrap(g.getFontMetrics(), p[3], 440)) { g.drawString(ln, 690, yy); yy += 36; }
        g.setFont(font(Font.ITALIC, 18)); g.setColor(new Color(110, 90, 70));
        ctr(g, (jIdx + 1) + " / " + cnt, 360, 640);
    }

    /* ---------- BRANCH TREE ---------- */
    void node(Graphics2D g, int cx, int cy, Color c, boolean glow, String mark) {
        if (glow) {
            g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), 34, new float[]{0, 1}, new Color[]{alpha(c, 110), alpha(c, 0)}));
            g.fillOval(cx - 34, cy - 34, 68, 68);
        }
        g.setColor(new Color(12, 6, 8)); g.fillRoundRect(cx - 17, cy - 17, 34, 34, 8, 8);
        g.setColor(c); g.setStroke(new BasicStroke(2)); g.drawRoundRect(cx - 17, cy - 17, 34, 34, 8, 8);
        g.setFont(font(Font.BOLD, 20));
        ctr(g, mark, cx, cy + 7);
    }

    void drawTree(Graphics2D g) {
        g.setPaint(new GradientPaint(0, 0, new Color(16, 6, 10), 0, H, Color.BLACK)); g.fillRect(0, 0, W, H);
        g.setFont(font(Font.PLAIN, 46)); g.setColor(Color.WHITE);
        g.drawString("\u2767 Branch Tree \u2767", 80, 80);
        g.setFont(font(Font.ITALIC, 20)); g.setColor(GOLD);
        g.drawString(tests.get(testIdx).title, 80, 112);
        int n = path.size(), total = n + 1;
        int step = Math.min(150, (W - 260) / Math.max(1, total)), y = 290, x0 = 130;
        g.setColor(RED); g.setStroke(new BasicStroke(3));
        g.drawLine(x0, y, x0 + n * step, y);
        for (int i = 0; i < n; i++) {
            Scene s = scenes.get(path.get(i)[0]);
            int chosen = Integer.parseInt(path.get(i)[1]), x = x0 + i * step, up = 0;
            for (int j = 0; j < s.ch.length; j++) {
                int cx = x + step / 2;
                if (j == chosen) { node(g, cx, y, Color.WHITE, true, "\u2666"); continue; }
                int cy = y + ((up % 2 == 0) ? -1 : 1) * (70 + (up / 2) * 55);
                up++;
                g.setColor(new Color(220, 220, 220, 150)); g.setStroke(new BasicStroke(2));
                g.drawLine(x, y, x, cy); g.drawLine(x, cy, cx, cy);
                node(g, cx, cy, new Color(200, 200, 200, 150), false, "\u2666");
            }
            node(g, x, y, RED, true, "\u2261");
        }
        node(g, x0 + n * step, y, GOLD_L, true, "!");
        g.setFont(font(Font.PLAIN, 16));
        for (int i = 0; i < n; i++) {
            Scene s = scenes.get(path.get(i)[0]);
            Choice c = s.ch[Integer.parseInt(path.get(i)[1])];
            int col = i % 2, row = i / 2;
            g.setColor(GOLD); g.drawString((i + 1) + ". " + s.title + ":", 90 + col * 560, 470 + row * 46);
            g.setColor(new Color(215, 205, 190)); g.drawString("   \"" + c.text + "\"", 90 + col * 560, 470 + row * 46 + 20);
        }
        link(g, "\u25C0 Back", 80, 700, () -> st = prevSt);
    }

    /* ---------- TEST RESULT ---------- */
    void bars(Graphics2D g, int x, int y, int w, int[] v, int max, boolean dark) {
        for (int i = 0; i < 5; i++) {
            g.setFont(font(Font.PLAIN, 20)); g.setColor(dark ? new Color(60, 40, 30) : Story.TCOL[i]);
            g.drawString(Story.TRAITS[i] + "  " + Math.max(0, v[i]), x, y + i * 46);
            g.setColor(dark ? new Color(60, 40, 30, 60) : new Color(255, 255, 255, 40)); g.fillRoundRect(x, y + i * 46 + 8, w, 12, 8, 8);
            g.setColor(dark ? Story.TCOL[i].darker() : Story.TCOL[i]);
            g.fillRoundRect(x, y + i * 46 + 8, Math.max(6, Math.min(w, w * Math.max(0, v[i]) / max)), 12, 8, 8);
        }
    }

    void drawEnd(Graphics2D g) {
        bgBack(g, 3); bgFront(g, 3);
        g.setColor(new Color(0, 0, 0, 150)); g.fillRect(0, 0, W, H);
        Test t = tests.get(testIdx);
        String[] e = t.end[endIdx];
        if (endPage == 0) {
            drawTitle(g, e[0], W / 2, 130, 62);
            g.setFont(font(Font.ITALIC, 26)); g.setColor(new Color(220, 200, 170));
            ctr(g, "Test " + (testIdx + 1) + " finished: " + t.title, W / 2, 175);
            panel(g, 120, 200, 1040, 170, 20, 225, false);
            g.setFont(font(Font.PLAIN, 27)); g.setColor(new Color(245, 237, 220));
            int y = 252;
            for (String ln : wrap(g.getFontMetrics(), fmt(e[1]), 960)) { g.drawString(ln, 160, y); y += 38; }
            g.setFont(font(Font.ITALIC, 20)); g.setColor(GOLD);
            ctr(g, "Your scores in this test", W / 2, 410);
            int[] d = delta();
            for (int i = 0; i < 5; i++) {
                int x = 110 + i * 215;
                g.setFont(font(Font.PLAIN, 19)); g.setColor(Story.TCOL[i]);
                g.drawString(Story.TRAITS[i] + "  " + Math.max(0, d[i]), x, 450);
                g.setColor(new Color(255, 255, 255, 40)); g.fillRoundRect(x, 460, 170, 12, 8, 8);
                g.setColor(Story.TCOL[i]); g.fillRoundRect(x, 460, Math.max(6, Math.min(170, 170 * Math.max(0, d[i]) / 9)), 12, 8, 8);
            }
            button(g, "Your Choices", 300, 540, 250, 54, () -> endPage = 1);
            button(g, allDone() ? "See Final Profile" : "Next Test", 640, 540, 290, 54,
                () -> { if (allDone()) st = St.PROFILE; else { st = St.SELECT; audio.playTrack(0); } fade = 1f; });
            button(g, "Main Menu", 980, 540, 250, 54, () -> st = St.MENU);
        } else {
            g.setFont(font(Font.BOLD, 50)); g.setColor(GOLD_L); g.drawString("Your choices", 80, 90);
            for (int i = 0; i < path.size(); i++) {
                Scene s = scenes.get(path.get(i)[0]);
                Choice c = s.ch[Integer.parseInt(path.get(i)[1])];
                int x = 60 + (i % 2) * 600, y = 110 + (i / 2) * 112;
                g.setPaint(new GradientPaint(0, y, new Color(110, 14, 24, 235), 0, y + 96, new Color(50, 6, 12, 235)));
                g.fillRect(x, y, 560, 96);
                g.setColor(GOLD); g.setStroke(new BasicStroke(1.5f)); g.drawRect(x, y, 560, 96);
                g.setFont(font(Font.BOLD, 24)); g.setColor(GOLD_L); ctr(g, s.title, x + 280, y + 32);
                g.setFont(font(Font.PLAIN, 18)); g.setColor(new Color(230, 215, 190));
                int yy = y + 60;
                for (String ln : wrap(g.getFontMetrics(), "You chose: " + c.text, 520)) { ctr(g, ln, x + 280, yy); yy += 22; }
            }
            button(g, "Back", W / 2, 650, 240, 50, () -> endPage = 0);
        }
    }

    /* ---------- FINAL PROFILE ---------- */
    void drawProfile(Graphics2D g) {
        bgBack(g, 7); bgFront(g, 7);
        g.setColor(new Color(0, 0, 0, 170)); g.fillRect(0, 0, W, H);
        drawTitle(g, "Your Personality Profile", W / 2, 90, 54);
        int[] order = {0, 1, 2, 3, 4};
        Integer[] idx = {0, 1, 2, 3, 4};
        Arrays.sort(idx, (a, b) -> tr[b] != tr[a] ? tr[b] - tr[a] : a - b);
        int first = idx[0], second = idx[1];
        int n = 0; for (boolean b : done) if (b) n++;
        if (n == 0) {
            g.setFont(font(Font.PLAIN, 26)); g.setColor(Color.WHITE);
            ctr(g, "Finish a test first, then your profile will appear here.", W / 2, 300);
        } else {
            panel(g, 80, 130, 520, 400, 20, 225, false);
            g.setFont(font(Font.ITALIC, 22)); g.setColor(GOLD);
            g.drawString("Total scores from " + n + (n == 1 ? " test" : " tests"), 110, 175);
            int max = 1; for (int v : tr) max = Math.max(max, v);
            bars(g, 110, 215, 400, tr, Math.max(8, max), false);
            panel(g, 640, 130, 560, 400, 20, 225, false);
            g.setFont(font(Font.ITALIC, 22)); g.setColor(GOLD); g.drawString("You are...", 670, 175);
            g.setFont(font(Font.BOLD, 40)); g.setColor(GOLD_L);
            String title = "The " + Story.ADJ[second] + " " + Story.NOUN[first];
            for (String ln : wrap(g.getFontMetrics(), title, 500)) { g.drawString(ln, 670, 230); }
            g.setFont(font(Font.PLAIN, 24)); g.setColor(new Color(245, 237, 220));
            int y = 290;
            String desc = Story.NOUN_DESC[first] + " You also have " + Story.SIDE_DESC[second] + ".";
            for (String ln : wrap(g.getFontMetrics(), desc, 500)) { g.drawString(ln, 670, y); y += 34; }
            g.setFont(font(Font.ITALIC, 18)); g.setColor(new Color(200, 185, 160));
            y += 14;
            for (int i = 0; i < 4; i++) if (result[i] != null) { g.drawString("Test " + (i + 1) + ": " + result[i], 670, y); y += 26; }
            if (!allDone()) { g.setColor(GOLD); g.drawString("Finish all 4 tests for the most accurate result.", 670, 500); }
        }
        button(g, "Back to Tests", 480, 580, 280, 54, () -> st = SelectOrMenu());
        button(g, "Main Menu", 800, 580, 280, 54, () -> st = St.MENU);
    }

    St SelectOrMenu() { return name.isEmpty() ? St.MENU : St.SELECT; }

    /* ======================= INPUT ======================= */
    @Override public void mousePressed(MouseEvent e) {
        for (Btn b : new ArrayList<>(btns))
            if (b.r.contains(e.getPoint())) { if (b.act != null) b.act.run(); return; }
        if (st == St.STORY && cur != null && shown < fmt(cur.text).length()) shown = fmt(cur.text).length();
    }
    @Override public void mouseMoved(MouseEvent e) {
        mouse = e.getPoint();
        String k = "";
        for (Btn b : btns) if (b.r.contains(mouse) && b.act != null) { k = b.label; break; }
        if (!k.isEmpty() && !k.equals(hoverKey)) audio.hover();
        hoverKey = k;
    }
    @Override public void mouseDragged(MouseEvent e) { mouseMoved(e); }
    @Override public void keyTyped(KeyEvent e) {
        char c = e.getKeyChar();
        if (st == St.NAME) {
            if (c == '\b') { if (!name.isEmpty()) name = name.substring(0, name.length() - 1); }
            else if (c == '\n') { if (!name.trim().isEmpty()) { name = name.trim(); newGame(); } }
            else if ((Character.isLetterOrDigit(c) || c == ' ') && name.length() < 14) name += c;
        } else if (st == St.STORY && (c == ' ' || c == '\n') && cur != null) shown = fmt(cur.text).length();
        else if (c == 27 && st != St.MENU) st = St.MENU;
    }
    @Override public void keyPressed(KeyEvent e) { }
    @Override public void keyReleased(KeyEvent e) { }
    @Override public void mouseClicked(MouseEvent e) { }
    @Override public void mouseReleased(MouseEvent e) { }
    @Override public void mouseEntered(MouseEvent e) { }
    @Override public void mouseExited(MouseEvent e) { }
}
