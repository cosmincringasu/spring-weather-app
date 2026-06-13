package com.example.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
public class JokeController {

    // The same joke is served all day and rotates to the next one each day.
    private static final String[][] JOKES = {
        {"Why don't scientists trust atoms?", "Because they make up everything."},
        {"Why did the scarecrow win an award?", "He was outstanding in his field."},
        {"Why don't skeletons fight each other?", "They don't have the guts."},
        {"What do you call fake spaghetti?", "An impasta."},
        {"Why did the bicycle fall over?", "It was two tired."},
        {"How does a penguin build its house?", "Igloos it together."},
        {"Why can't your nose be 12 inches long?", "Because then it would be a foot."},
        {"What do you call a bear with no teeth?", "A gummy bear."},
        {"Why did the math book look so sad?", "It had too many problems."},
        {"What do you call cheese that isn't yours?", "Nacho cheese."},
        {"Why did the developer go broke?", "Because he used up all his cache."},
        {"How do you comfort a JavaScript bug?", "You console it."},
        {"Why do programmers prefer dark mode?", "Because light attracts bugs."},
        {"What's a computer's favorite snack?", "Microchips."},
        {"Why was the robot angry?", "Someone kept pushing its buttons."},
        {"Why did the coffee file a police report?", "It got mugged."},
        {"What do you call a fish wearing a bowtie?", "Sofishticated."},
        {"Why did the tomato turn red?", "It saw the salad dressing."},
        {"How do you organize a space party?", "You planet."},
        {"Why don't eggs tell jokes?", "They'd crack each other up."},
        {"What did the ocean say to the beach?", "Nothing, it just waved."},
        {"Why did the golfer bring two pairs of pants?", "In case he got a hole in one."},
        {"What do you call a sleeping dinosaur?", "A dino-snore."},
        {"Why can't you give Elsa a balloon?", "Because she'll let it go."},
        {"What's orange and sounds like a parrot?", "A carrot."},
        {"Why did the stadium get hot after the game?", "All the fans left."},
        {"How do you make a tissue dance?", "You put a little boogie in it."},
        {"What do you call a dog that can do magic?", "A labracadabrador."},
        {"Why did the picture go to jail?", "It was framed."},
        {"What do you call a factory that makes okay products?", "A satisfactory."},
        {"Why did the cookie go to the doctor?", "Because it was feeling crummy."}
    };

    @GetMapping("/api/joke")
    public JokeResponse getJoke() {
        LocalDate today = LocalDate.now();
        int index = today.getDayOfYear() % JOKES.length;
        String[] joke = JOKES[index];
        return new JokeResponse(joke[0], joke[1], today.toString());
    }
}
