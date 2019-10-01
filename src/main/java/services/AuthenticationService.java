package services;

import data.transferobjects.UpdatePasswordVO;
import data.transferobjects.UserVO;
import repositories.Repository;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * @author Alexander Burghuber
 */
@Path("auth")
public class AuthenticationService {
    private Repository repo = new Repository();

    /**
     * Registers a new user
     *
     * @param user the Transfer Object of the User entity
     * @return a json that includes either the newly registered user or all validation errors
     */
    @Path("register")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response register(UserVO user) {
        return repo.register(
                user.getUsername(),
                user.getEmail(),
                user.getPassword()
        );
    }

    /**
     * Logs an user in
     *
     * @param user the Transfer Object of the User entity
     * @return a json that includes either the jwt or an error
     */
    @Path("login")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response login(UserVO user) {
        return repo.login(user.getUsername(), user.getPassword());
    }

    /**
     * Verifies the email of an user with an unique token that was sent with the email confirmation
     *
     * @param emailToken the unique token
     * @return a Response that redirects the user
     */
    @Path("verify/{token}")
    @GET
    public Response verify(@PathParam("token") String emailToken) {
        URI location = null;
        try {
            // when the right token was given, send the user to the login page telling him it was successful else tell him there was something wrong
            location = new URI("http://localhost:4200/login?token="
                    + repo.verify(emailToken));
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        System.out.println(location);
        return Response.temporaryRedirect(location).build();
    }

    /**
     * Request a change on the password that is linked to the email
     *
     * @param user the verified email of an user
     * @return a status code (OK, Conflict)
     */
    @Path("requestPasswordChange")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response requestPasswordChange(UserVO user) {
        return repo.requestPasswordChange(user.getEmail());
    }

    /**
     * Update the password of an user
     *
     * @param updatePassword the Transfer Object for updating the password
     * @return a status code (OK, Conflict, Forbidden)
     */
    @Path("updatePassword")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updatePassword(UpdatePasswordVO updatePassword) {
        return repo.updatePassword(updatePassword.getPin(), updatePassword.getPassword());
    }
}