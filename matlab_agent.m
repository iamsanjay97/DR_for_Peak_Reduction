function matlab_agent(output_port)

    import java.net.ServerSocket
    import java.io.*

    while true

        try

            fprintf(1, ['\nWaiting for client to connect to port : %d\n'], output_port);

            % wait for 1 second for client to connect server socket
            server_socket = ServerSocket(output_port);
            server_socket.setSoTimeout(70000);

            in_socket = server_socket.accept();

            fprintf(1, 'Client connected\n');

            % get a buffered data input stream from the socket
            in_stream   = in_socket.getInputStream();
            d_in_stream = DataInputStream(in_stream);

            pause(0.5);
            bytes_available = in_stream.available();
            fprintf(1, 'Reading %d bytes\n', bytes_available)

            message = zeros(1, bytes_available, 'uint8');
            for i = 1:bytes_available
                message(i) = d_in_stream.readByte;
            end
            
            % solve optimization problem
            message = char(message);        
            X = str2num(message);
            customers = X(1);
            U = X(2:X(1)+1);
            UP = X(X(1)+2:2*X(1)+1);
            l = X(2*X(1)+2:3*X(1)+1);
            r = X(3*X(1)+2);
            P = X(3*X(1)+3);

            opt_sol = optimization(customers, U, UP, l, r, P);
            display(opt_sol);

            % Convert MATLAB array to a Java JSONArray
            jsonArray = jsonencode(opt_sol);

            % Convert JSONArray to a string in JSON format
            % jsonString = jsonArray.toString();

            out_stream = in_socket.getOutputStream();
            d_out_stream = DataOutputStream(out_stream);
            d_out_stream.writeBytes(jsonArray);
            d_out_stream.flush();

            % clean up
            in_stream.close();
            out_stream.close();
            d_in_stream.close();
            d_out_stream.close();
            in_socket.close();
            server_socket.close();
        catch e
            fprintf('Error\n');
            fprintf(e.message, '\n');
            in_socket.close();
            server_socket.close();
            pause(1);
        end
    end
end


function opt_sol= optimization(customers, U, UP, l, r, P)

    r_inv = 100 / r;
    % Create optimization variables
    x3 = optimvar("x",customers,"LowerBound",0,"UpperBound",r);

    % Set initial starting point for the solver
    initialPoint2.x = zeros(size(x3));

    % Create problem
    problem = optimproblem;

    % Define problem objective
    problem.Objective = fcn2optimexpr(@objectiveFn,x3,customers,r_inv,l);

    % Define problem constraints
    problem.Constraints = constraintFcn(x3,customers,r_inv,r,l,U,UP,P);

    % Display problem information
    % show(problem);

    % Solve problem
    [solution,objectiveValue,~] = solve(problem,initialPoint2);

    % Display results
    % display(solution);
    % display(objectiveValue);
    % display(solution.x);
    opt_sol = solution.x;  
    
end


function objective = objectiveFn(x, customers, r_inv, l)

    % This function should return a scalar representing an optimization objective.
    objective = 0;
    for i=1:customers
        objective = objective + exp(-r_inv*l(i)*x(i)) - 1;
    end
    
end


function constraints = constraintFcn(x, customers, r_inv, r, l, U, UP, P)

    total_U = 0;
    for i=1:customers
        total_U = total_U + U(i);
    end

    temp = 0;
    for i=1:customers
        temp = temp + x(i)*(U(i) - exp(-r_inv*l(i)*x(i))*UP(i));
    end

    right = r*total_U/100.0;
    right = right*P;

    constraints(1) = temp <= right;

end