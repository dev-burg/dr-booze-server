package repositories;

import entities.PersonBO;
import entities.UserBO;
import entities.VerificationToken;
import helper.EntityManagerFactoryHelper;
import helper.JwtHelper;
import helper.ValidatorHelper;
import mail.Mail;
import objects.ErrorGenerator;
import org.bouncycastle.util.encoders.Hex;
import org.json.JSONObject;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.TypedQuery;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author Alexander Burghuber
 */
@SuppressWarnings("Duplicates")
public class Repository {

    private EntityManager em;
    private ErrorGenerator errorgen;
    private ValidatorHelper validator;
    private JwtHelper jwtHelper;
    private Mail mail;
    private ExecutorService executor = Executors.newFixedThreadPool(10);

    private static Repository instance = null;

    private Repository() {
        EntityManagerFactory emf = EntityManagerFactoryHelper.getFactory();
        this.em = emf.createEntityManager();
        this.validator = new ValidatorHelper();
        this.errorgen = new ErrorGenerator();
        this.jwtHelper = new JwtHelper();
        this.mail = new Mail();
    }

    public static Repository getInstance() {
        if (instance == null)
            instance = new Repository();
        return instance;
    }

    /**
     * Registers a new user and validates his input
     *
     * @param username the username of the user
     * @param email    the email of the user
     * @param password the password of the user
     * @return a json String that includes either the newly registered user or all validation errors
     */
    public String register(final String username, final String email, final String password) {
        UserBO user = new UserBO(username, email, password);

        // validate the user
        String resultUser = validator.validateUser(user);
        if (resultUser != null)
            return resultUser;

        TypedQuery<Long> queryUniqueName = em.createNamedQuery("User.count-username", Long.class)
                .setParameter("username", username);
        long numberOfEntriesName = queryUniqueName.getSingleResult();

        TypedQuery<Long> queryUniqueEmail = em.createNamedQuery("User.count-email", Long.class)
                .setParameter("email", email);
        long numberOfEntriesEmail = queryUniqueEmail.getSingleResult();

        // check if the username or the email is already taken
        if (numberOfEntriesName != 0) {
            return errorgen.generate(602, "username");
        }
        if (numberOfEntriesEmail != 0) {
            return errorgen.generate(602, "email");
        }

        // setup the verification token of the user
        VerificationToken verificationToken = new VerificationToken(user);
        System.out.println("Setup token: " + verificationToken.getToken()
                + " Expire: " + verificationToken.getExpiryDate());

        // persist the new user
        em.getTransaction().begin();
        em.persist(user);
        em.persist(verificationToken);
        em.getTransaction().commit();

        // multithreaded email sending
        executor.execute(() -> {
            System.out.println("Sending email confirmation...");
            mail.sendConfirmation(user, verificationToken);
            System.out.println("Email confirmation sent.");
        });

        // return user as json
        JSONObject json = new JSONObject();
        json.put("user", user.toJson());
        String jsonString = json.toString();
        System.out.println(jsonString);
        return jsonString;
    }

    /**
     * Logs the user in if the username and password is correct
     *
     * @param username the username of the user
     * @param password the password of the user
     * @return a json containing either the user if the login was successful or an error code
     */
    public String login(final String username, final String password) {
        // check if the username exists in the database
        TypedQuery<UserBO> queryGetUser = em.createNamedQuery("User.get-with-username", UserBO.class)
                .setParameter("username", username);
        List<UserBO> resultsGetUser = queryGetUser.getResultList();

        if (resultsGetUser.size() == 0) {
            return errorgen.generate(605, "login");
        }

        // check if the password is correct
        UserBO user = resultsGetUser.get(0);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Hex.decode(user.getSalt()));
            byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));

            if (!new String(Hex.encode(hash)).equals(user.getPasswordHash())) {
                return errorgen.generate(605, "login");
            }
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        String jwtToken = jwtHelper.create(user.getUsername());
        JSONObject json = new JSONObject();
        json.put("token", jwtToken);

        System.out.println("Logged in: " + user.getUsername() + " with token: " + jwtToken);
        return json.toString();
    }

    /**
     * Verifies an user using the token that was send with the url inside the verification email.
     *
     * @param emailToken the verification token
     * @return a boolean that indicates if the verification was successful or not
     */
    public boolean verify(final String emailToken) {
        // check if the token exists
        List<VerificationToken> tokenList
                = em.createQuery("SELECT v FROM VerificationToken v WHERE v.token = :token", VerificationToken.class)
                .setParameter("token", emailToken)
                .getResultList();
        if (tokenList.size() != 0) {
            VerificationToken verifyToken = tokenList.get(0);

            Date currentDate = new Date();
            Date tokenDate = verifyToken.getExpiryDate();

            if (tokenDate.compareTo(currentDate) >= 0) {
                UserBO user = verifyToken.getUser();
                // set the user enabled and delete the token
                em.getTransaction().begin();
                user.setEnabled(true);
                em.remove(verifyToken);
                em.getTransaction().commit();
                return true;
            }
        }
        return false;
    }

    /**
     * @param jwt the json web token
     * @return a json string that includes the person
     */
    public String getPerson(final String jwt) {
        UserBO user = getUserFromJwt(jwt);
        if (user == null)
            return errorgen.generate(607, "user");

        PersonBO person = user.getPerson();

        JSONObject json = new JSONObject();
        if (person == null) {
            json.put("person", JSONObject.NULL);
        } else {
            json.put("person", person.toJson());
        }
        String jsonString = json.toString();
        System.out.println(jsonString);
        return jsonString;
    }

    /**
     * Inserts the details of an user as a person object
     *
     * @param jwt       the json web token
     * @param firstName the first name of the user
     * @param lastName  the last name of the user
     * @param gender    the gender of the user
     * @param birthday  the birthday of the user
     * @param height    the height of the user
     * @param weight    the weight of the user
     * @return a json String that includes either the user or all validation errors
     */
    public String insertDetails(final String jwt, final String firstName, final String lastName, final String gender,
                                final Date birthday, final double height, final double weight) {
        // check if the gender, height and weight is incorrect
        if (!gender.equals("m") && !gender.equals("f")) {
            return errorgen.generate(604, "gender");
        } else if (height < 150.0 || height > 230.0) {
            return errorgen.generate(604, "height");
        } else if (weight < 30 || weight > 200) {
            return errorgen.generate(604, "weight");
        }

        UserBO user = getUserFromJwt(jwt);
        if (user == null)
            return errorgen.generate(607, "user");

        PersonBO person = new PersonBO(user, firstName, lastName, gender, birthday, height, weight);
        user.setPerson(person);

        // validate the person
        String resultPerson = validator.validatePerson(person);
        if (resultPerson != null)
            return resultPerson;

        System.out.println(person.toString());
        System.out.println(person.getUser().toString());

        // persist the updated user & person
        em.getTransaction().begin();
        em.persist(person);
        em.getTransaction().commit();

        // return the user and person
        JSONObject json = new JSONObject();
        json.put("person", person.toJson());
        String jsonString = json.toString();
        System.out.println(jsonString);
        return jsonString;
    }

    /**
     * Updates the details of an user
     *
     * @param jwt       the json web token
     * @param password  the new password of the user
     * @param firstName the new firstName of the user
     * @param lastName  the new lastName of the user
     * @param gender    the new gender of the user
     * @param birthday  the new birthday of the user
     * @param height    the new height of the user
     * @param weight    the new weight of the user
     * @return a json String that includes either the user or all validation errors
     */
    public String updateDetails(final String jwt, final String password, final String firstName,
                                final String lastName, final String gender, final Date birthday,
                                final double height, final double weight) {

        System.out.println("jwt: " + jwt + " password: " + password + " firstName: " + firstName
                + " lastName: " + lastName + " gender: " + gender + " birthday: " + birthday
                + " height: " + height + " weight: " + weight);

        UserBO user = getUserFromJwt(jwt);
        if (user == null)
            return errorgen.generate(607, "user");

        PersonBO person = user.getPerson();
        if (person == null)
            return errorgen.generate(607, "person");

        // set the new value if the value is not null
        if (password != null)
            user.setPassword(password);
        if (firstName != null)
            person.setFirstName(firstName);
        if (lastName != null)
            person.setLastName(lastName);
        if (birthday != null)
            person.setBirthday(birthday);
        if (gender != null) {
            if (!gender.equals("m") && !gender.equals("f"))
                return errorgen.generate(604, "gender");
            person.setGender(gender);
        }
        if (height != 0) {
            if (height < 150.0 || height > 230.0)
                return errorgen.generate(604, "height");
            person.setHeight(height);
        }
        if (weight != 0) {
            if (weight < 30 || weight > 200)
                return errorgen.generate(604, "weight");
            person.setWeight(weight);
        }

        // validate the user
        String resultUser = validator.validateUser(user);
        if (resultUser != null)
            return resultUser;

        // validate the person
        String resultPerson = validator.validatePerson(person);
        if (resultPerson != null)
            return resultPerson;

        System.out.println(person.toJson().toString());

        // persist the updated user & person
        em.getTransaction().begin();
        em.persist(user);
        em.getTransaction().commit();

        // return the user and person
        JSONObject json = new JSONObject();
        json.put("person", person.toJson());
        String jsonString = json.toString();
        System.out.println(jsonString);
        return jsonString;
    }

    private UserBO getUserFromJwt(String jwt) {
        String username = jwtHelper.checkSubject(jwt);

        TypedQuery<UserBO> queryGetUser = em.createNamedQuery("User.get-with-username", UserBO.class)
                .setParameter("username", username);
        List<UserBO> resultsGetUser = queryGetUser.getResultList();

        // check if user exists
        if (resultsGetUser.size() == 0) {
            return null;
        }
        return resultsGetUser.get(0);
    }

    public void loadAlcohol() throws IOException {
        //JSONParser parser = new JSONParser();

        //Object obj = parser.parse();
        List<String> lines = Files.readAllLines(Paths.get("src/main/resources/alcohol/beers.json"));
        JSONObject json = new JSONObject(lines.toString());
        System.out.println(json.toString());
    }

}